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

import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;

import org.junit.jupiter.api.BeforeAll;

/**
 * Base class for integration tests with proper external port configuration.
 * <p>
 * This class configures REST Assured to use the external test port
 * that is configured via Maven properties and Docker port mapping.
 * Tests should always access the application from the outside perspective.
 */
public abstract class BaseIntegrationTest {

    private static final String DEFAULT_TEST_PORT = "10443";
    private static final String DEFAULT_MANAGEMENT_PORT = "19000";

    /**
     * The gateway's APPLICATION context path ({@code quarkus.http.root-path}) at its shipped default.
     * Overridable with {@code -Dtest.http.root-path} so a suite can be pointed at an image built with
     * a moved context path, which is a rebuild rather than a runtime switch — the key is build-time
     * fixed. See {@code doc/user/context-path.adoc}.
     */
    private static final String DEFAULT_HTTP_ROOT_PATH = "/";

    /**
     * The gateway's MANAGEMENT context path ({@code quarkus.management.root-path}) at its shipped
     * default. It is one of three hand-maintained spellings under {@code integration-tests/} --
     * {@code prometheus.yml} and {@code verify-invalid-config-fails.sh} carry the others -- and all
     * three are now asserted against the Compose label by {@link ManagementRootPathLabelIT}, so a
     * value that drifts from the label fails the build rather than silently probing the wrong path.
     * It is an INDEPENDENT ABSOLUTE key: it does not move when {@code quarkus.http.root-path} moves,
     * which is why the two are separate properties here rather than one composed value.
     */
    private static final String DEFAULT_MANAGEMENT_ROOT_PATH = "/q";

    @BeforeAll
    static void setUpBaseIntegrationTest() {
        // Configure HTTPS with relaxed certificate validation for tests
        RestAssured.useRelaxedHTTPSValidation();
        RestAssured.baseURI = "https://localhost";

        // Use the external test port from Maven properties (Docker port mapping 10443:8443)
        String testPort = System.getProperty("test.https.port", DEFAULT_TEST_PORT);
        RestAssured.port = Integer.parseInt(testPort);

        // Every request this suite issues is resolved beneath the gateway's application context path,
        // so a suite run against a relocated root path needs no per-test edit. Normalised so the
        // shipped default "/" collapses to the empty string — REST Assured's own default — leaving
        // the default-path behaviour byte-identical to before this key existed.
        String httpRootPath = normalisePath(System.getProperty("test.http.root-path", DEFAULT_HTTP_ROOT_PATH));
        RestAssured.basePath = httpRootPath;

        // cui-rewrite:disable CuiLoggerStandardsRecipe
        IO.println("Integration tests configured for HTTPS port: " + testPort
                + ", application root path: '" + httpRootPath + "'");
    }

    /**
     * Returns the management interface base URI (HTTPS, port 19000) <strong>including the management
     * context path</strong>. Health and metrics endpoints are served on the management port when
     * {@code quarkus.management.enabled=true}, beneath {@code quarkus.management.root-path}.
     * <p>
     * The root path is PART of the returned value rather than restated by each caller: it is
     * build-time fixed configuration that a deployment can move, so a caller appending its own
     * copy of the management context path would be a second spelling to keep in lockstep. Callers
     * therefore append ONLY the endpoint beneath it — {@code managementBaseUri() + "/health/ready"} —
     * and never re-prefix the context path they already received.
     * <p>
     * The scheme is HTTPS, not plain HTTP: Quarkus' management interface has exactly one port
     * (its config declares no {@code ssl-port} and no {@code insecure-requests}), so supplying
     * {@code quarkus.management.ssl.certificate.*} — which the compose stack now does — converts
     * port 9000 itself to HTTPS. No plain-HTTP management listener remains. The self-signed
     * certificate needs no extra work here: {@link #setUpBaseIntegrationTest()} already calls
     * {@link RestAssured#useRelaxedHTTPSValidation()}.
     *
     * @return management base URI, including the management context path, for health/metrics endpoints
     */
    static String managementBaseUri() {
        return managementPortBaseUri() + managementRootPath();
    }

    /**
     * The management interface origin — scheme, host and port — with NO context path.
     * <p>
     * Exposed separately so a test that must build a management URL from an INDEPENDENTLY obtained
     * path (the Compose label, say) can do so without the configured path being spliced in first.
     * {@link ManagementRootPathLabelIT} needs exactly that: composing the label onto
     * {@link #managementBaseUri()} would prepend the path twice, and — worse — would make the
     * assertion circular, checking the configured path against itself.
     *
     * @return the management origin, without any context path
     */
    static String managementPortBaseUri() {
        return "https://localhost:" + System.getProperty("test.management.port", DEFAULT_MANAGEMENT_PORT);
    }

    /**
     * The management context path, normalised for concatenation.
     *
     * @return the configured management context path, normalised for concatenation
     */
    static String managementRootPath() {
        return normalisePath(System.getProperty("test.management.root-path", DEFAULT_MANAGEMENT_ROOT_PATH));
    }

    /**
     * Builds a request specification aimed at the MANAGEMENT interface.
     * <p>
     * Clearing {@code basePath} is load-bearing rather than tidy-up: the management interface is
     * <em>not</em> served beneath {@code quarkus.http.root-path}. {@code quarkus.management.root-path}
     * is an independent absolute key on a separate port, so leaving the application base path in place
     * would splice it into every management URL and address an endpoint that does not exist the moment
     * a suite runs with a relocated application context path.
     *
     * @return a request specification bound to the management base URI with no application base path
     */
    static RequestSpecification givenManagement() {
        return RestAssured.given().baseUri(managementBaseUri()).basePath("");
    }

    /**
     * Normalises a context path for concatenation: a trailing slash is removed, so appending
     * {@code "/health"} never yields a double slash, and the root value {@code "/"} collapses to the
     * empty string rather than leaving one behind.
     * <p>
     * Package-private rather than private so a test comparing an INDEPENDENTLY obtained path against
     * this class's configured one applies the identical rule to both sides.
     * {@link ManagementRootPathLabelIT} needs exactly that: it compares the Compose label against
     * {@link #managementRootPath()}, and normalising only one side would report a {@code "/"} label
     * and the empty effective path as a divergence when they denote the same path.
     *
     * @param path the configured context path
     * @return the path with any trailing slash removed
     */
    static String normalisePath(String path) {
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}