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

import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.CERTIFICATES;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.DOCKER;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.RETRY_SCHEDULED_RECORD;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.assertReportsDown;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.awaitLogRecord;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.awaitReadiness;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.composeNetwork;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.docker;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.dockerQuietly;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.gatewayLog;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.mintIntegrationRealmToken;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.publishedPort;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.readinessData;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.securedAssetStatus;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.startGateway;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

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
 * {@link NoCertificatePlainHttpOptInIT} established, through the shared {@link OneOffGatewayContainers}
 * harness: the same {@value OneOffGatewayContainers#IMAGE} image every gateway instance runs, started
 * with {@code docker run} and torn down on every exit path.
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

    /** The late provider's image — the same pinned nginx the compose stack's TLS fixtures run. */
    private static final String PROXY_IMAGE = "nginx:1.27-alpine";

    /**
     * The late provider's network alias, and the host the late issuer's {@code jwks.url} names. It is
     * one of the three names the IT certificate's SAN list covers; see the descriptor's header for why
     * it is not {@code keycloak}.
     */
    private static final String PROXY_ALIAS = "api-sheriff";

    private static final Path LATE_GATEWAY = DOCKER.resolve(Path.of("sheriff-config-late-idp", "gateway.yaml"));
    private static final Path PROXY_CONFIG = DOCKER.resolve(Path.of("late-idp", "nginx.conf"));

    /** The late issuer's configured name — which the DOWN payload must never disclose. */
    private static final String ISSUER_NAME = "late-keycloak";

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

    /** What the DOWN payload must never name: the issuer, the host and port fetched from, the URL. */
    private static final List<String> UNDISCLOSED =
            List.of(ISSUER_NAME, PROXY_ALIAS + ":8443", "realms/integration", "https://");

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
            startGateway(gateway, network, LATE_GATEWAY);
            String managementOrigin = "https://localhost:" + publishedPort(gateway, 9000);
            String applicationOrigin = "https://localhost:" + publishedPort(gateway, 8443);
            String bearer = mintIntegrationRealmToken();

            // Act + Assert (1) — DOWN while the provider is absent
            Response down = awaitReadiness(gateway, managementOrigin, response -> true, BOOT_TIMEOUT_SECONDS,
                    "the late gateway's management interface to answer");
            assertReportsDown(gateway, down, UNDISCLOSED);
            assertEquals(401, securedAssetStatus(applicationOrigin, bearer), () -> "matched control: with no key "
                    + "set loaded the gateway cannot validate the provider's token, so it must be rejected 401. "
                    + gatewayLog(gateway));
            awaitLogRecord(gateway, RETRY_SCHEDULED_RECORD, RETRY_SCHEDULED_TIMEOUT_SECONDS,
                    "once the first load attempt has spent the HTTP client's own retries it must fail and the "
                            + "gateway must schedule its retry, announced by WARN " + RETRY_SCHEDULED_RECORD);
            assertReportsDown(gateway, awaitReadiness(gateway, managementOrigin, response -> true,
                    BOOT_TIMEOUT_SECONDS, "the late gateway's readiness after the failed first attempt"), UNDISCLOSED);

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

    private static void createProxy(String proxy, String network) {
        docker("create the late provider", "create",
                "--name", proxy,
                "--network", network,
                "--network-alias", PROXY_ALIAS,
                "-v", PROXY_CONFIG.toAbsolutePath() + ":/etc/nginx/conf.d/default.conf:ro",
                "-v", CERTIFICATES.toAbsolutePath() + ":/etc/nginx/certificates:ro",
                PROXY_IMAGE);
    }
}
