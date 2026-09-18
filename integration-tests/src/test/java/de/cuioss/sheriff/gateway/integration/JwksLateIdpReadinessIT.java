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
import java.util.UUID;
import java.util.function.Predicate;

import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the JWKS readiness gap is closed end to end in the shipped native image (AS-10): a gateway
 * whose identity provider is not reachable at boot reports readiness {@code DOWN}, and once the
 * provider appears the gateway's fast, bounded retry loads the key set, readiness turns {@code UP}
 * within seconds rather than after a whole refresh interval, and the provider's tokens are accepted.
 * <p>
 * <strong>Why a one-off {@code docker run} rather than a compose service.</strong> The compose
 * readiness gate in {@code start-integration-container.sh} requires every {@code api-sheriff*}
 * service to report {@code UP} before any suite runs, and this gateway is {@code DOWN} by design until
 * the test itself starts its identity provider. So the test drives its own containers, in the shape
 * {@link NoCertificatePlainHttpOptInIT} established: the same {@value #IMAGE} image every gateway
 * instance runs, started with {@code docker run} and torn down on every exit path.
 * <p>
 * <strong>The topology.</strong> Everything lives on a dedicated network created per run, so nothing
 * the compose stack resolves can stand in for the late provider:
 * <ul>
 *   <li>the gateway boots over {@code sheriff-config-late-idp/gateway.yaml} — one bearer-gated
 *       directory asset route and exactly one issuer, whose {@code jwks.url} names
 *       {@value #PROXY_ALIAS}{@code :8443};</li>
 *   <li>the late provider is a TLS nginx proxy ({@code late-idp/nginx.conf}) in front of the compose
 *       Keycloak's certs endpoint. It carries the network alias {@value #PROXY_ALIAS} on the
 *       dedicated network and is also attached to the compose network so it can reach the real
 *       Keycloak. The test <em>creates</em> it before the gateway boots and <em>starts</em> it only
 *       after it has observed the gateway {@code DOWN}; a created container's alias does not
 *       resolve, so every load attempt before that point fails.</li>
 * </ul>
 * <p>
 * <strong>What each leg proves, and the control that makes it mean something.</strong>
 * <ol>
 *   <li><em>DOWN while the provider is absent</em> — the readiness payload reports {@code DOWN} with
 *       {@code jwks} {@code loading} or {@code unavailable} and {@code issuers_loaded} below
 *       {@code issuers}, and names no issuer, URL or host. The same token that is accepted later is
 *       rejected {@code 401} here, which is the matched control for the acceptance leg: the
 *       acceptance is caused by the loaded key set, not by a route that admits anything.</li>
 *   <li><em>The first load attempt gives up and the gateway schedules its own retry</em> — the log
 *       carries {@code WARN ApiSheriff-129}. This is awaited for
 *       {@value #RETRY_SCHEDULED_TIMEOUT_SECONDS}s, not for a few seconds, because a load attempt is
 *       not one HTTP request: the HTTP client beneath the token library retries the {@code GET}
 *       itself ({@code HTTP-112}, "GET request failed on attempt N, retrying after ..."), with a
 *       jittered backoff of roughly 1, 2, 4 and 8 seconds across five attempts. The attempt reports
 *       failure — and the gateway logs {@code ApiSheriff-129} — only once that inner budget of about
 *       17 seconds is spent. The provider is started only <em>after</em> this record, which is what
 *       makes the recovery below a recovery of the gateway's retry path: a provider started while the
 *       first attempt is still retrying internally would be picked up by that attempt, with no
 *       gateway retry and no {@code ApiSheriff-18} to attribute it by.</li>
 *   <li><em>UP within {@value #RECOVERY_BOUND_SECONDS}s of the provider appearing</em> — see the
 *       constant for how the bound is derived. The gateway's
 *       configuration model exposes no refresh-interval key, so the issuer runs the token library's
 *       default interval, which a recovery that waited for the background refresh would have to sit
 *       out. {@code INFO ApiSheriff-18} ("loaded after N retry attempt(s)") is asserted beside the
 *       verdict, so the recovery is attributed to the retry path rather than inferred from timing
 *       alone.</li>
 *   <li><em>The provider's token is accepted</em> — a Keycloak-issued bearer token reaches the
 *       bearer-gated route and is answered {@code 200}. This is what proves the library's recovery path
 *       works through the gateway-owned loader: a key set that loaded without the issuer being
 *       re-admitted would still reject the token.</li>
 * </ol>
 * <p>
 * The test is one method on purpose: the legs are successive states of the same two containers, so
 * splitting them would make each depend on the previous one's side effects through test ordering.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Late IdP: readiness is DOWN until the key set loads, then recovers fast and accepts tokens")
class JwksLateIdpReadinessIT {

    /** The image every gateway instance in this stack runs, and the one under test here. */
    private static final String IMAGE = "api-sheriff:distroless";

    /** The late provider's image — the same pinned nginx the compose stack's TLS fixtures run. */
    private static final String PROXY_IMAGE = "nginx:1.27-alpine";

    /**
     * The late provider's network alias, and the host the late issuer's {@code jwks.url} names. It is
     * one of the three names the IT certificate's SAN list covers; see the descriptor's header for why
     * it is not {@code keycloak}.
     */
    private static final String PROXY_ALIAS = "api-sheriff";

    private static final Path DOCKER = Path.of("src", "main", "docker");
    private static final Path CERTIFICATES = DOCKER.resolve("certificates");
    private static final Path LATE_GATEWAY = DOCKER.resolve(Path.of("sheriff-config-late-idp", "gateway.yaml"));
    private static final Path ASSETS_SECURE_ENDPOINT =
            DOCKER.resolve(Path.of("sheriff-config", "endpoints", "assets-secure.yaml"));
    private static final Path ASSETS = DOCKER.resolve("assets");
    private static final Path PROXY_CONFIG = DOCKER.resolve(Path.of("late-idp", "nginx.conf"));

    /** The bearer-gated directory asset the acceptance leg fetches; served from the mounted assets. */
    private static final String SECURE_ASSET = "/secure-assets/app.css";

    /** The host-published Keycloak origin the test JVM can reach (compose {@code 1443 -> 8443}). */
    private static final String KEYCLOAK_ORIGIN = "https://localhost:1443";

    /** The compose service whose network the late provider joins to reach the real Keycloak. */
    private static final String KEYCLOAK_SERVICE = "keycloak";

    private static final String READINESS_CHECK = "gateway-readiness";

    /** The late issuer's configured name — which the DOWN payload must never disclose. */
    private static final String ISSUER_NAME = "late-keycloak";

    /** WARN — a load attempt produced no key set and a retry was scheduled. */
    private static final String RETRY_SCHEDULED_RECORD = "ApiSheriff-129";

    /** INFO — the key set was loaded by a retry, closing the episode. */
    private static final String LOADED_AFTER_RETRY_RECORD = "ApiSheriff-18";

    /** Upper bound for the gateway's management interface to answer at all after {@code docker run}. */
    private static final long BOOT_TIMEOUT_SECONDS = 90L;

    /**
     * Upper bound for the first load attempt to give up and {@code ApiSheriff-129} to appear, counted
     * from the first {@code DOWN} answer. A load attempt ends only once the HTTP client's own retries
     * are spent: five {@code GET}s separated by a jittered backoff of roughly 1, 2, 4 and 8 seconds —
     * observed on CI as 1072, 2013, 3888 and 8202 ms, about 17 s in total — plus the time each failed
     * {@code GET} itself takes. Twice that budget plus margin for a loaded runner.
     */
    private static final long RETRY_SCHEDULED_TIMEOUT_SECONDS = 60L;

    /**
     * Upper bound for readiness to turn {@code UP} once the provider has started. A provider that
     * appears while an attempt is still retrying internally is picked up by that attempt's next
     * {@code GET}, so the worst case is the provider appearing just after an attempt's <em>last</em>
     * inner {@code GET} failed: recovery then waits out the next gateway retry delay (at most 30 s,
     * capped at the refresh interval) plus that retry's first {@code GET}, leaving the bound generous
     * slack. Because the provider is started as
     * soon as the first {@code ApiSheriff-129} is seen, the realistic case is far shorter — the first
     * gateway retry is scheduled 1 s out, and the provider is normally reachable by that retry's first
     * or second {@code GET}.
     */
    private static final long RECOVERY_BOUND_SECONDS = 60L;

    /** Upper bound for a log record to appear after the state it announces was observed. */
    private static final long LOG_TIMEOUT_SECONDS = 10L;

    private static final long POLL_INTERVAL_MILLIS = 500L;

    /** Upper bound on any single docker call. */
    private static final long DOCKER_TIMEOUT_SECONDS = 60L;

    @Test
    @DisplayName("DOWN without the provider, UP within seconds of it appearing, and its token is then accepted")
    void lateIdentityProviderTurnsReadinessUpAndItsTokensAreAccepted() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String network = "late-idp-it-" + suffix;
        String proxy = "late-idp-proxy-" + suffix;
        String gateway = "late-idp-gateway-" + suffix;
        try {
            // Arrange — the dedicated network, the created-but-not-started provider, the gateway
            docker("create the dedicated network", "network", "create", network);
            createProxy(proxy, network);
            docker("attach the late provider to the compose network", "network", "connect", composeNetwork(), proxy);
            startGateway(gateway, network);
            String managementOrigin = "https://localhost:" + publishedPort(gateway, 9000);
            String applicationOrigin = "https://localhost:" + publishedPort(gateway, 8443);
            String bearer = mintIntegrationRealmToken();

            // Act + Assert (1) — DOWN while the provider is absent
            Response down = awaitReadiness(gateway, managementOrigin, response -> true, BOOT_TIMEOUT_SECONDS,
                    "the late gateway's management interface to answer");
            assertReportsDown(gateway, down);
            assertEquals(401, securedAssetStatus(applicationOrigin, bearer), () -> "matched control: with no key "
                    + "set loaded the gateway cannot validate the provider's token, so it must be rejected 401. "
                    + gatewayLog(gateway));
            awaitLogRecord(gateway, RETRY_SCHEDULED_RECORD, RETRY_SCHEDULED_TIMEOUT_SECONDS,
                    "once the first load attempt has spent the HTTP client's own retries it must fail and the "
                            + "gateway must schedule its retry, announced by WARN " + RETRY_SCHEDULED_RECORD);
            assertReportsDown(gateway, awaitReadiness(gateway, managementOrigin, response -> true,
                    BOOT_TIMEOUT_SECONDS, "the late gateway's readiness after the failed first attempt"));

            // Act (2) — the provider appears
            docker("start the late provider", "start", proxy);
            long startedAt = System.nanoTime();

            // Assert (2) — UP within the bound, attributed to the retry path
            Response up = awaitReadiness(gateway, managementOrigin, response -> response.statusCode() == 200,
                    RECOVERY_BOUND_SECONDS, "readiness to turn UP after the late provider started");
            long recoverySeconds = Duration.ofNanos(System.nanoTime() - startedAt).toSeconds();
            assertAll("readiness recovered through the fast retry",
                    () -> assertEquals("UP", up.path("status"), () -> "overall readiness: " + up.asString()),
                    () -> assertEquals("ready", readinessData(up, "jwks"), () -> up.asString()),
                    () -> assertEquals(1, ((Number) readinessData(up, "issuers_loaded")).intValue(),
                            () -> up.asString()),
                    () -> assertTrue(recoverySeconds <= RECOVERY_BOUND_SECONDS,
                            () -> "recovery took " + recoverySeconds + "s"));
            awaitLogRecord(gateway, LOADED_AFTER_RETRY_RECORD, LOG_TIMEOUT_SECONDS,
                    "the recovery must be closed by INFO " + LOADED_AFTER_RETRY_RECORD
                            + " — a key set loaded by the retry, not by the library's background refresh");

            // Act + Assert (3) — the provider's token is accepted, and the route still refuses no token
            assertEquals(200, securedAssetStatus(applicationOrigin, bearer), () -> "once the key set is loaded "
                    + "the provider's token must be accepted on the bearer-gated route. " + gatewayLog(gateway));
            assertEquals(401, securedAssetStatus(applicationOrigin, null),
                    "control: the route still refuses a request without a token");
        } finally {
            dockerQuietly("rm", "-f", gateway, proxy);
            dockerQuietly("network", "rm", network);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Assertions
    // ---------------------------------------------------------------------------------------------

    /**
     * Asserts the readiness payload is a non-disclosing {@code DOWN}.
     */
    private static void assertReportsDown(String gateway, Response down) {
        String body = down.asString();
        Object jwks = readinessData(down, "jwks");
        assertAll("readiness is DOWN while the late issuer has no key set",
                () -> assertEquals(503, down.statusCode(), () -> "readiness must answer 503 while DOWN: " + body),
                () -> assertEquals("DOWN", down.path("status"), () -> body),
                () -> assertTrue(Set.of("loading", "unavailable").contains(jwks),
                        () -> "jwks must be 'loading' or 'unavailable', was " + jwks + ": " + body),
                () -> assertEquals(1, ((Number) readinessData(down, "issuers")).intValue(), () -> body),
                () -> assertEquals(0, ((Number) readinessData(down, "issuers_loaded")).intValue(), () -> body));
        // Non-disclosing: the management interface may legitimately be plain HTTP, so the payload owes
        // the caller a state, never the issuer's name, its URL or the host the key set is fetched from.
        for (String secret : List.of(ISSUER_NAME, PROXY_ALIAS + ":8443", "realms/integration", "https://")) {
            assertFalse(body.contains(secret), () -> "the DOWN payload must not disclose '" + secret + "': " + body
                    + " " + gatewayLog(gateway));
        }
    }

    /**
     * Reads one datum of the gateway's own readiness check out of the SmallRye health payload.
     */
    private static Object readinessData(Response response, String key) {
        Object value = response.path("checks.find { it.name == '" + READINESS_CHECK + "' }.data." + key);
        assertNotNull(value, () -> "the " + READINESS_CHECK + " check carries no '" + key + "' datum: "
                + response.asString());
        return value;
    }

    /**
     * Polls the readiness endpoint until an answer satisfies {@code accepted}.
     *
     * @return the first accepted answer
     */
    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - bounded poll of an external container's state
    private static Response awaitReadiness(String gateway, String managementOrigin, Predicate<Response> accepted,
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
     * @throws IOException while the interface is not answering yet. RestAssured runs on Groovy and
     *                     rethrows the HTTP client's connection-level failure — a refused connection,
     *                     a reset, an unanswered request, an aborted TLS handshake, all
     *                     {@link IOException}s — without declaring it; declaring it here is what lets
     *                     the poll catch exactly that family rather than every runtime failure
     */
    @SuppressWarnings("java:S1130") // NOSONAR java:S1130 - RestAssured rethrows IOException undeclared (Groovy)
    private static Response readiness(String managementOrigin) throws IOException {
        return given()
                .relaxedHTTPSValidation()
                .baseUri(managementOrigin)
                .basePath("")
                .when()
                .get(BaseIntegrationTest.managementRootPath() + "/health/ready");
    }

    /**
     * Polls the gateway's merged container output until it carries {@code record}.
     *
     * @param timeoutSeconds how long the record may take to appear
     */
    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - bounded wait for a container log line
    private static void awaitLogRecord(String gateway, String record, long timeoutSeconds, String why) {
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
     * Fetches the bearer-gated asset and reports the status.
     *
     * @param bearer the access token to present, or {@code null} to send no {@code Authorization}
     */
    private static int securedAssetStatus(String applicationOrigin, String bearer) {
        var request = given().relaxedHTTPSValidation().baseUri(applicationOrigin).basePath("");
        if (bearer != null) {
            request = request.header("Authorization", "Bearer " + bearer);
        }
        return request.when().get(SECURE_ASSET).statusCode();
    }

    /**
     * Mints an access token from the compose Keycloak's {@code integration} realm. The realm pins its
     * frontend URL, so the token's {@code iss} is the container-internal issuer the late descriptor
     * declares, whichever origin the token was minted through.
     */
    private static String mintIntegrationRealmToken() {
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
    // Harness
    // ---------------------------------------------------------------------------------------------

    private static void createProxy(String proxy, String network) {
        docker("create the late provider", "create",
                "--name", proxy,
                "--network", network,
                "--network-alias", PROXY_ALIAS,
                "-v", PROXY_CONFIG.toAbsolutePath() + ":/etc/nginx/conf.d/default.conf:ro",
                "-v", CERTIFICATES.toAbsolutePath() + ":/etc/nginx/certificates:ro",
                PROXY_IMAGE);
    }

    /**
     * Starts the late gateway. The environment mirrors a compose gateway instance whose descriptor
     * declares no {@code passthrough_sni}, so Quarkus terminates TLS directly on 8443; both ports are
     * published on ephemeral loopback ports so the run cannot collide with the live stack.
     */
    private static void startGateway(String gateway, String network) {
        docker("start the late gateway", "run", "-d",
                "--name", gateway,
                "--network", network,
                "-p", "127.0.0.1::8443",
                "-p", "127.0.0.1::9000",
                "-e", "QUARKUS_PROFILE=it",
                // Binds the benchmark-idp trust profile the late issuer names to the stack's trust store.
                "-e", "QUARKUS_CONFIG_LOCATIONS=/app/certificates/benchmark-idp-trust.properties",
                "-e", "QUARKUS_HTTP_SSL_CERTIFICATE_FILES=/app/certificates/localhost.crt",
                "-e", "QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES=/app/certificates/localhost.key",
                "-e", "QUARKUS_MANAGEMENT_SSL_CERTIFICATE_FILES=/app/certificates/localhost.crt",
                "-e", "QUARKUS_MANAGEMENT_SSL_CERTIFICATE_KEY_FILES=/app/certificates/localhost.key",
                "-e", "SHERIFF_CONFIG_DIR=/app/sheriff-config",
                "-v", CERTIFICATES.toAbsolutePath() + ":/app/certificates:ro",
                "-v", LATE_GATEWAY.toAbsolutePath() + ":/app/sheriff-config/gateway.yaml:ro",
                "-v", ASSETS_SECURE_ENDPOINT.toAbsolutePath() + ":/app/sheriff-config/endpoints/assets-secure.yaml:ro",
                "-v", ASSETS.toAbsolutePath() + ":/app/assets:ro",
                IMAGE);
    }

    /**
     * The compose network the real Keycloak is attached to, read from the running project rather than
     * restated — the project name follows {@code COMPOSE_PROJECT_NAME} or the module directory, and the
     * network name follows the project.
     */
    private static String composeNetwork() {
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
     */
    private static String publishedPort(String container, int containerPort) {
        String mapping = docker("read the published port " + containerPort, "port", container, containerPort + "/tcp")
                .lines().findFirst().orElse("");
        int colon = mapping.lastIndexOf(':');
        assertTrue(colon > 0 && colon < mapping.length() - 1,
                () -> "unexpected docker port mapping for " + containerPort + ": '" + mapping + "'");
        return mapping.substring(colon + 1).strip();
    }

    private static String gatewayLog(String gateway) {
        return "Gateway log:\n" + dockerQuietly("logs", gateway);
    }

    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - the poll cadence of a bounded external wait
    private static void sleepPollInterval() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while polling the late gateway", interrupted);
        }
    }

    /**
     * Runs one docker command and returns its merged output, failing the test on a non-zero exit.
     */
    private static String docker(String description, String... arguments) {
        DockerRun run = runDocker(arguments);
        assertEquals(0, run.exitCode(), () -> "could not " + description + " — is " + IMAGE
                + " built and the compose stack up? Output: " + run.output());
        return run.output();
    }

    /**
     * Runs one docker command for teardown or diagnostics, where a failure must not mask the outcome
     * of the test it served.
     */
    private static String dockerQuietly(String... arguments) {
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
    private record DockerRun(int exitCode, String output) {
    }

    /**
     * Runs one docker command, waiting for it before reading its output — which is redirected to a
     * file so a large output (a container log) can never fill a pipe and deadlock the wait.
     */
    private static DockerRun runDocker(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Path captured = null;
        try {
            captured = Files.createTempFile("api-sheriff-late-idp-", ".out");
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
