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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import io.restassured.response.Response;

/**
 * The shared harness for integration tests that boot a gateway as a one-off {@code docker run}
 * container instead of as a compose service — the gateways whose readiness is {@code DOWN} by design
 * for some or all of their life, which the compose readiness gate in
 * {@code start-integration-container.sh} would refuse.
 * <p>
 * It carries the docker process plumbing, the port and network lookups, the readiness and log polls,
 * the bearer-gated asset probe and the token mint, so each such test states only its own topology and
 * legs. Every gateway it starts runs the {@value #IMAGE} image the compose stack runs, mounts one
 * standalone {@code gateway.yaml} beside exactly one shared endpoint file
 * ({@code sheriff-config/endpoints/assets-secure.yaml}) and no {@code topology.properties}, and
 * publishes both of its ports on ephemeral loopback ports so a run cannot collide with the live stack.
 * <p>
 * Not instantiable; every member is a stateless static helper. Docker container names are global to
 * the docker daemon, so callers give each container a unique name.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
final class OneOffGatewayContainers {

    /** The image every gateway instance in this stack runs, and the one each one-off gateway runs. */
    static final String IMAGE = "api-sheriff:distroless";

    /** The docker fixture tree, relative to the module root failsafe runs in. */
    static final Path DOCKER = Path.of("src", "main", "docker");

    /** The certificate directory every gateway mounts at {@code /app/certificates}. */
    static final Path CERTIFICATES = DOCKER.resolve("certificates");

    /** The bearer-gated directory asset every one-off gateway fetches; served from the mounted assets. */
    static final String SECURE_ASSET = "/secure-assets/app.css";

    /** WARN — a load attempt produced no key set and a retry was scheduled. */
    static final String RETRY_SCHEDULED_RECORD = "ApiSheriff-129";

    private static final Path ASSETS_SECURE_ENDPOINT =
            DOCKER.resolve(Path.of("sheriff-config", "endpoints", "assets-secure.yaml"));
    private static final Path ASSETS = DOCKER.resolve("assets");

    /** The host-published Keycloak origin the test JVM can reach (compose {@code 1443 -> 8443}). */
    private static final String KEYCLOAK_ORIGIN = "https://localhost:1443";

    /** The compose service whose network a one-off container joins to reach the real Keycloak. */
    private static final String KEYCLOAK_SERVICE = "keycloak";

    private static final String READINESS_CHECK = "gateway-readiness";

    private static final long POLL_INTERVAL_MILLIS = 500L;

    /** Upper bound on any single docker call. */
    private static final long DOCKER_TIMEOUT_SECONDS = 60L;

    private OneOffGatewayContainers() {
        // static helpers only
    }

    // ---------------------------------------------------------------------------------------------
    // Gateway lifecycle
    // ---------------------------------------------------------------------------------------------

    /**
     * Starts a one-off gateway over a standalone descriptor. The environment mirrors a compose gateway
     * instance whose descriptor declares no {@code passthrough_sni}, so Quarkus terminates TLS directly
     * on 8443; both ports are published on ephemeral loopback ports so the run cannot collide with the
     * live stack.
     *
     * @param gateway    the unique container name
     * @param network    the docker network the container joins
     * @param descriptor the standalone {@code gateway.yaml} mounted as the gateway's global document
     */
    static void startGateway(String gateway, String network, Path descriptor) {
        docker("start the one-off gateway " + gateway, "run", "-d",
                "--name", gateway,
                "--network", network,
                "-p", "127.0.0.1::8443",
                "-p", "127.0.0.1::9000",
                "-e", "QUARKUS_PROFILE=it",
                // Binds the benchmark-idp trust profile the issuer names to the stack's trust store.
                "-e", "QUARKUS_CONFIG_LOCATIONS=/app/certificates/benchmark-idp-trust.properties",
                "-e", "QUARKUS_HTTP_SSL_CERTIFICATE_FILES=/app/certificates/localhost.crt",
                "-e", "QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES=/app/certificates/localhost.key",
                "-e", "QUARKUS_MANAGEMENT_SSL_CERTIFICATE_FILES=/app/certificates/localhost.crt",
                "-e", "QUARKUS_MANAGEMENT_SSL_CERTIFICATE_KEY_FILES=/app/certificates/localhost.key",
                "-e", "SHERIFF_CONFIG_DIR=/app/sheriff-config",
                "-v", CERTIFICATES.toAbsolutePath() + ":/app/certificates:ro",
                "-v", descriptor.toAbsolutePath() + ":/app/sheriff-config/gateway.yaml:ro",
                "-v", ASSETS_SECURE_ENDPOINT.toAbsolutePath() + ":/app/sheriff-config/endpoints/assets-secure.yaml:ro",
                "-v", ASSETS.toAbsolutePath() + ":/app/assets:ro",
                IMAGE);
    }

    /**
     * The compose network the real Keycloak is attached to, read from the running project rather than
     * restated — the project name follows {@code COMPOSE_PROJECT_NAME} or the module directory, and the
     * network name follows the project.
     *
     * @return the name of the single network the compose Keycloak container is attached to
     */
    static String composeNetwork() {
        Map<String, String> containers = ContainerHealthInspector.composeContainers();
        String keycloak = containers.get(KEYCLOAK_SERVICE);
        assertNotNull(keycloak, () -> "the compose project runs no '" + KEYCLOAK_SERVICE + "' service: " + containers);
        List<String> networks = docker("read the Keycloak container's networks", "inspect", "--format",
                "{{range $name, $settings := .NetworkSettings.Networks}}{{println $name}}{{end}}", keycloak)
                .lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        assertEquals(1, networks.size(), () -> "expected the compose Keycloak on exactly one network, found " + networks);
        return networks.getFirst();
    }

    /**
     * The loopback host port docker published for a container port.
     *
     * @param container     the container name
     * @param containerPort the port inside the container
     * @return the published host port
     */
    static String publishedPort(String container, int containerPort) {
        String mapping = docker("read the published port " + containerPort, "port", container, containerPort + "/tcp")
                .lines().findFirst().orElse("");
        int colon = mapping.lastIndexOf(':');
        assertTrue(colon > 0 && colon < mapping.length() - 1,
                () -> "unexpected docker port mapping for " + containerPort + ": '" + mapping + "'");
        return mapping.substring(colon + 1).strip();
    }

    // ---------------------------------------------------------------------------------------------
    // Readiness
    // ---------------------------------------------------------------------------------------------

    /**
     * Polls the readiness endpoint until an answer satisfies {@code accepted}.
     *
     * @param gateway          the container name, for the diagnostic log on timeout
     * @param managementOrigin the published management origin
     * @param accepted         the predicate the awaited answer satisfies
     * @param timeoutSeconds   how long to poll
     * @param what             what is awaited, for the failure message
     * @return the first accepted answer
     */
    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - bounded poll of an external container's state
    static Response awaitReadiness(String gateway, String managementOrigin, Predicate<Response> accepted,
            long timeoutSeconds, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        String lastObservation = "no answer yet";
        while (System.nanoTime() < deadline) {
            try {
                Response response = readiness(managementOrigin);
                if (accepted.test(response)) {
                    return response;
                }
                lastObservation = response.statusCode() + " " + response.asString();
            } catch (IOException notAnsweringYet) {
                lastObservation = notAnsweringYet.toString();
            }
            sleepPollInterval();
        }
        return fail("timed out after " + timeoutSeconds + "s waiting for " + what + ". Last observation: "
                + lastObservation + " " + gatewayLog(gateway));
    }

    /**
     * One readiness request against the management interface.
     *
     * @param managementOrigin the published management origin
     * @return the readiness answer, whatever its status
     * @throws IOException while the interface is not answering yet. RestAssured runs on Groovy and
     *                     rethrows the HTTP client's connection-level failure — a refused connection,
     *                     a reset, an unanswered request, an aborted TLS handshake, all
     *                     {@link IOException}s — without declaring it; declaring it here is what lets
     *                     the poll catch exactly that family rather than every runtime failure
     */
    @SuppressWarnings("java:S1130") // NOSONAR java:S1130 - RestAssured rethrows IOException undeclared (Groovy)
    static Response readiness(String managementOrigin) throws IOException {
        return given()
                .relaxedHTTPSValidation()
                .baseUri(managementOrigin)
                .basePath("")
                .when()
                .get(BaseIntegrationTest.managementRootPath() + "/health/ready");
    }

    /**
     * Reads one datum of the gateway's own readiness check out of the SmallRye health payload.
     *
     * @param response the readiness answer
     * @param key      the datum name
     * @return the datum's value; never {@code null} — a missing datum fails the assertion instead
     */
    static Object readinessData(Response response, String key) {
        Object value = response.path("checks.find { it.name == '" + READINESS_CHECK + "' }.data." + key);
        assertNotNull(value, () -> "the " + READINESS_CHECK + " check carries no '" + key + "' datum: "
                + response.asString());
        return value;
    }

    /**
     * Asserts the readiness payload of a single-issuer gateway is a non-disclosing {@code DOWN}: 503,
     * {@code jwks} {@code loading} or {@code unavailable}, one issuer configured and none loaded, and
     * none of {@code undisclosed} anywhere in the payload. The management interface may legitimately be
     * plain HTTP, so the payload owes the caller a state, never the issuer's name, its URL or the host
     * the key set is fetched from.
     *
     * @param gateway     the container name, for the diagnostic log
     * @param down        the readiness answer
     * @param undisclosed the literals the payload must not contain
     */
    static void assertReportsDown(String gateway, Response down, List<String> undisclosed) {
        String body = down.asString();
        Object jwks = readinessData(down, "jwks");
        assertAll("readiness is DOWN while the issuer has no key set",
                () -> assertEquals(503, down.statusCode(), () -> "readiness must answer 503 while DOWN: " + body),
                () -> assertEquals("DOWN", down.path("status"), () -> body),
                () -> assertTrue(Set.of("loading", "unavailable").contains(jwks),
                        () -> "jwks must be 'loading' or 'unavailable', was " + jwks + ": " + body),
                () -> assertEquals(1, ((Number) readinessData(down, "issuers")).intValue(), () -> body),
                () -> assertEquals(0, ((Number) readinessData(down, "issuers_loaded")).intValue(), () -> body));
        for (String secret : undisclosed) {
            assertFalse(body.contains(secret), () -> "the DOWN payload must not disclose '" + secret + "': " + body
                    + " " + gatewayLog(gateway));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Logs
    // ---------------------------------------------------------------------------------------------

    /**
     * Polls the gateway's merged container output until it carries {@code record}.
     *
     * @param gateway        the container name
     * @param record         the log record identifier awaited
     * @param timeoutSeconds how long the record may take to appear
     * @param why            what the record proves, for the failure message
     */
    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - bounded wait for a container log line
    static void awaitLogRecord(String gateway, String record, long timeoutSeconds, String why) {
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        while (System.nanoTime() < deadline) {
            if (docker("read the gateway log", "logs", gateway).contains(record)) {
                return;
            }
            sleepPollInterval();
        }
        fail(why + " (waited " + timeoutSeconds + "s). " + gatewayLog(gateway));
    }

    /**
     * The gateway's merged container output, prefixed for use in a failure message.
     *
     * @param gateway the container name
     * @return the log, or a marker when docker itself could not produce it
     */
    static String gatewayLog(String gateway) {
        return "Gateway log:\n" + dockerQuietly("logs", gateway);
    }

    // ---------------------------------------------------------------------------------------------
    // Requests
    // ---------------------------------------------------------------------------------------------

    /**
     * Fetches the bearer-gated asset and reports the status.
     *
     * @param applicationOrigin the published application origin
     * @param bearer            the access token to present, or {@code null} to send no {@code Authorization}
     * @return the HTTP status answered
     */
    static int securedAssetStatus(String applicationOrigin, String bearer) {
        var request = given().relaxedHTTPSValidation().baseUri(applicationOrigin).basePath("");
        if (bearer != null) {
            request = request.header("Authorization", "Bearer " + bearer);
        }
        return request.when().get(SECURE_ASSET).statusCode();
    }

    /**
     * Mints an access token from the compose Keycloak's {@code integration} realm. The realm pins its
     * frontend URL, so the token's {@code iss} is the container-internal issuer
     * ({@code https://keycloak:8443/realms/integration}) the one-off descriptors declare, whichever
     * origin the token was minted through.
     *
     * @return the access token
     */
    static String mintIntegrationRealmToken() {
        String token = given().relaxedHTTPSValidation()
                .contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "password")
                .formParam("client_id", "integration-client")
                .formParam("client_secret", "integration-secret")
                .formParam("username", "integration-user")
                .formParam("password", "integration-password")
                .formParam("scope", "openid")
                .when().post(KEYCLOAK_ORIGIN + "/realms/integration/protocol/openid-connect/token")
                .then().statusCode(200)
                .extract().path("access_token");
        assertNotNull(token, "the integration realm must mint an access token");
        return token;
    }

    // ---------------------------------------------------------------------------------------------
    // Docker process plumbing
    // ---------------------------------------------------------------------------------------------

    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - the poll cadence of a bounded external wait
    private static void sleepPollInterval() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while polling a one-off gateway", interrupted);
        }
    }

    /**
     * Runs one docker command and returns its merged output, failing the test on a non-zero exit.
     *
     * @param description what the command does, for the failure message
     * @param arguments   the docker arguments
     * @return the merged, stripped output
     */
    static String docker(String description, String... arguments) {
        DockerRun run = runDocker(arguments);
        assertEquals(0, run.exitCode(), () -> "could not " + description + " — is " + IMAGE
                + " built and the compose stack up? Output: " + run.output());
        return run.output();
    }

    /**
     * Runs one docker command for teardown or diagnostics, where a failure must not mask the outcome
     * of the test it served.
     *
     * @param arguments the docker arguments
     * @return the merged output, or a marker naming the failure
     */
    static String dockerQuietly(String... arguments) {
        try {
            return runDocker(arguments).output();
        } catch (UncheckedIOException | IllegalStateException failure) {
            // Exactly the two failures runDocker raises: the process could not be started or read,
            // or the wait was interrupted.
            return "<docker " + String.join(" ", arguments) + " failed: " + failure + ">";
        }
    }

    /**
     * The outcome of one docker invocation.
     *
     * @param exitCode the process exit status
     * @param output   its merged stdout and stderr, stripped
     */
    record DockerRun(int exitCode, String output) {
    }

    /**
     * Runs one docker command, waiting for it before reading its output — which is redirected to a
     * file so a large output (a container log) can never fill a pipe and deadlock the wait.
     *
     * @param arguments the docker arguments
     * @return the exit status and merged output
     */
    static DockerRun runDocker(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Path captured = null;
        try {
            captured = Files.createTempFile("api-sheriff-one-off-gateway-", ".out");
            builder.redirectOutput(captured.toFile());
            Process process = builder.start();
            if (!process.waitFor(Duration.ofSeconds(DOCKER_TIMEOUT_SECONDS))) {
                process.destroyForcibly();
                return new DockerRun(-1, "timed out after " + DOCKER_TIMEOUT_SECONDS + "s: " + command);
            }
            return new DockerRun(process.exitValue(), Files.readString(captured).strip());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot run " + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running " + command, e);
        } finally {
            if (captured != null && !captured.toFile().delete()) {
                captured.toFile().deleteOnExit();
            }
        }
    }
}
