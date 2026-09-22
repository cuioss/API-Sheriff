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

import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.DOCKER;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.RETRY_SCHEDULED_RECORD;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.SECURE_ASSET;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.assertReportsDown;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.awaitLogRecord;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.awaitReadiness;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.composeNetwork;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.dockerQuietly;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.gatewayLog;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.mintIntegrationRealmToken;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.publishedPort;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.readinessData;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.securedAssetStatus;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.startGateway;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Proves in the shipped native image that an explicit JWKS egress allowlist is <em>authoritative</em>:
 * an issuer whose {@code jwks.allowed_egress_hosts} names a host other than the one its own
 * {@code jwks.url} fetches from keeps its key set refused by token-sheriff's SSRF egress guard
 * (GW-05 / BFF-07), and the gateway does not merge the host derived from {@code jwks.url} into that
 * list. Removing that one key — and only that key — lets the same key set load and the same token
 * pass, so the refusal is attributed to the explicit list and not to DNS, TLS or a dead identity
 * provider.
 * <p>
 * <strong>Why a one-off {@code docker run} rather than a compose service.</strong> The compose
 * readiness gate in {@code start-integration-container.sh} requires every {@code api-sheriff*}
 * service to report {@code UP} before any suite runs, and the gateway under test is {@code DOWN} by
 * design for its whole life. So the test drives its own containers through the shared
 * {@link OneOffGatewayContainers} harness — the same {@value OneOffGatewayContainers#IMAGE} image every
 * gateway instance runs, started with {@code docker run} and torn down on every exit path.
 * <p>
 * <strong>The topology.</strong> Both containers join the <em>compose</em> network, where
 * {@code keycloak} resolves to the real Keycloak's site-local bridge address — exactly the kind of
 * private address the egress guard refuses unless the host is allowed. The refused instance boots over
 * {@code sheriff-config-jwks-egress-mismatch/gateway.yaml}, whose single issuer declares
 * {@code allowed_egress_hosts: ["}{@value #DECLARED_HOST}{@code "]}: well-formed and portless, so boot
 * validation accepts it and only the egress guard can stop the fetch.
 * <p>
 * <strong>What each leg proves, and the control that makes it mean something.</strong>
 * <ol>
 *   <li><em>DOWN at boot, without disclosure</em> — the readiness payload reports {@code DOWN} with
 *       {@code jwks} {@code loading} or {@code unavailable} and no issuer loaded, and names no issuer,
 *       host, URL or allowlist entry.</li>
 *   <li><em>Still refused in steady state</em> — {@code WARN ApiSheriff-129} (a load attempt produced
 *       no key set and a retry was scheduled) is awaited, readiness is re-asserted {@code DOWN} after
 *       it, and a real Keycloak-minted token is rejected {@code 401} on the bearer-gated route. The
 *       refusal is therefore observed after a completed load attempt, not at an early instant before
 *       any attempt ran.</li>
 *   <li><em>The single-variable control</em> — the committed descriptor is parsed, the issuer's
 *       {@code allowed_egress_hosts} key is removed, the result is asserted to equal the parsed
 *       original minus exactly that key, and it is written under {@code target/}. A control gateway
 *       that is otherwise identical — same image, network, environment and mounts — then reaches
 *       readiness {@code UP} with the key set {@code ready}, and the <em>same</em> token is answered
 *       {@code 200} while no token is still answered {@code 401}. With the list gone the gateway
 *       derives the allowance from the {@code jwks.url} host, so this leg also proves the derived
 *       allowance end to end.</li>
 * </ol>
 * <p>
 * The single-variable control is the attribution for the refusal. The test does not additionally
 * assert a token-sheriff log record for the egress refusal itself: no stable record identifier for
 * that refusal was confirmed, and a message-text match would pin library wording rather than the
 * behaviour.
 * <p>
 * The test is one method on purpose: the legs are successive states of the same containers and the
 * same token, so splitting them would make each depend on the previous one's side effects through test
 * ordering.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("JWKS egress mismatch: an explicit allowlist naming another host keeps the key set refused")
class JwksEgressMismatchIT {

    private static final Path MISMATCH_GATEWAY =
            DOCKER.resolve(Path.of("sheriff-config-jwks-egress-mismatch", "gateway.yaml"));

    /** Where the single-variable control descriptor is written; a build output, never committed. */
    private static final Path CONTROL_GATEWAY = Path.of("target", "jwks-egress-mismatch-control", "gateway.yaml");

    /** The mismatch issuer's configured name — which the DOWN payload must never disclose. */
    private static final String ISSUER_NAME = "mismatch-keycloak";

    /** The one allowlist entry the mismatch descriptor declares; deliberately not the jwks.url host. */
    private static final String DECLARED_HOST = "not-the-jwks-host.invalid";

    private static final String ALLOWLIST_KEY = "allowed_egress_hosts";

    /** Upper bound for a gateway's management interface to answer — and for the control to load. */
    private static final long BOOT_TIMEOUT_SECONDS = 90L;

    /**
     * Upper bound for the first load attempt to fail and {@code ApiSheriff-129} to appear, counted from
     * the first {@code DOWN} answer — the same budget {@link JwksLateIdpReadinessIT} derives from the
     * HTTP client's own retries, so a refusal that is retried inside the attempt still fits.
     */
    private static final long RETRY_SCHEDULED_TIMEOUT_SECONDS = 60L;

    /** What the DOWN payload must never name: the issuer, the host and port fetched from, the URL, the entry. */
    private static final List<String> UNDISCLOSED =
            List.of(ISSUER_NAME, "keycloak:8443", "realms/integration", "https://", DECLARED_HOST);

    @Test
    @DisplayName("DOWN and 401 while the explicit list names another host; UP and 200 once that key alone is removed")
    void explicitAllowlistNamingAnotherHostKeepsTheKeySetRefused() throws IOException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String mismatch = "jwks-egress-mismatch-gateway-" + suffix;
        String control = "jwks-egress-mismatch-control-" + suffix;
        try {
            // Arrange — a real token, and the mismatch gateway on the compose network
            String network = composeNetwork();
            String bearer = mintIntegrationRealmToken();
            startGateway(mismatch, network, MISMATCH_GATEWAY);
            String managementOrigin = "https://localhost:" + publishedPort(mismatch, 9000);
            String applicationOrigin = "https://localhost:" + publishedPort(mismatch, 8443);

            // Act + Assert (1) — DOWN at boot, without disclosure
            Response down = awaitReadiness(mismatch, managementOrigin, response -> true, BOOT_TIMEOUT_SECONDS,
                    "the mismatch gateway's management interface to answer");
            assertReportsDown(mismatch, down, UNDISCLOSED);

            // Act + Assert (2) — still refused once a load attempt has completed and failed
            awaitLogRecord(mismatch, RETRY_SCHEDULED_RECORD, RETRY_SCHEDULED_TIMEOUT_SECONDS,
                    "the first load attempt must fail against the egress guard and the gateway must schedule "
                            + "its retry, announced by WARN " + RETRY_SCHEDULED_RECORD);
            assertReportsDown(mismatch, awaitReadiness(mismatch, managementOrigin, response -> true,
                    BOOT_TIMEOUT_SECONDS, "the mismatch gateway's readiness after the failed attempt"), UNDISCLOSED);
            assertEquals(401, securedAssetStatus(applicationOrigin, bearer), () -> "the explicit list names "
                    + "another host, so the key set must stay refused and the token must be rejected 401 on "
                    + SECURE_ASSET + ". A 200 here means the derived jwks.url host was merged into the list. "
                    + gatewayLog(mismatch));

            // Arrange (3) — the control: the committed descriptor minus that one key
            writeControlDescriptor();
            startGateway(control, network, CONTROL_GATEWAY);
            String controlManagementOrigin = "https://localhost:" + publishedPort(control, 9000);
            String controlApplicationOrigin = "https://localhost:" + publishedPort(control, 8443);

            // Act + Assert (3) — the same token passes once the list is absent
            Response up = awaitReadiness(control, controlManagementOrigin, response -> response.statusCode() == 200,
                    BOOT_TIMEOUT_SECONDS, "the control gateway's readiness to turn UP through the derived allowance");
            assertAll("the control loads its key set through the allowance derived from its jwks.url host",
                    () -> assertEquals("UP", up.path("status"), () -> "overall readiness: " + up.asString()),
                    () -> assertEquals("ready", readinessData(up, "jwks"), () -> up.asString()),
                    () -> assertEquals(1, ((Number) readinessData(up, "issuers_loaded")).intValue(),
                            () -> up.asString()));
            assertEquals(200, securedAssetStatus(controlApplicationOrigin, bearer), () -> "matched control: the "
                    + "same token must be accepted once only the allowlist key is removed, or the refusal above "
                    + "is not attributable to the explicit list. " + gatewayLog(control));
            assertEquals(401, securedAssetStatus(controlApplicationOrigin, null),
                    "control: the route still refuses a request without a token");
        } finally {
            dockerQuietly("rm", "-f", mismatch, control);
        }
    }

    /**
     * Writes the single-variable control descriptor: the committed mismatch descriptor with the
     * issuer's {@code allowed_egress_hosts} key removed and nothing else changed. The written file is
     * re-parsed and asserted to equal the parsed original minus that key, so the control cannot differ
     * from the refused instance in a second variable through a lossy YAML round trip.
     *
     * @throws IOException when the committed descriptor cannot be read or the control cannot be written
     */
    private static void writeControlDescriptor() throws IOException {
        Object control = loadYaml(MISMATCH_GATEWAY);
        Object removed = soleIssuerJwks(control).remove(ALLOWLIST_KEY);
        assertEquals(List.of(DECLARED_HOST), removed, () -> MISMATCH_GATEWAY + " must declare exactly ["
                + DECLARED_HOST + "] as the issuer's " + ALLOWLIST_KEY + ", or the refused leg tested something else");

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Files.createDirectories(CONTROL_GATEWAY.getParent());
        Files.writeString(CONTROL_GATEWAY, new Yaml(options).dump(control));

        Object original = loadYaml(MISMATCH_GATEWAY);
        Object written = loadYaml(CONTROL_GATEWAY);
        assertNotEquals(original, written, "the control must differ from the refused descriptor");
        soleIssuerJwks(original).remove(ALLOWLIST_KEY);
        assertEquals(original, written, () -> CONTROL_GATEWAY + " must equal " + MISMATCH_GATEWAY
                + " minus the issuer's " + ALLOWLIST_KEY + " key and nothing else");
    }

    /**
     * The {@code jwks} block of the descriptor's single issuer, asserted to be the mismatch issuer.
     *
     * @param document the parsed descriptor
     * @return the issuer's jwks block, mutable
     */
    private static Map<?, ?> soleIssuerJwks(Object document) {
        Map<?, ?> root = assertInstanceOf(Map.class, document, "the descriptor must parse to a mapping");
        Map<?, ?> tokenValidation = assertInstanceOf(Map.class, root.get("token_validation"),
                "the descriptor must declare a token_validation block");
        List<?> issuers = assertInstanceOf(List.class, tokenValidation.get("issuers"),
                "the descriptor must declare a token_validation.issuers list");
        assertEquals(1, issuers.size(), () -> "the descriptor must declare exactly one issuer: " + issuers);
        Map<?, ?> issuer = assertInstanceOf(Map.class, issuers.getFirst(), "the issuer must be a mapping");
        assertEquals(ISSUER_NAME, issuer.get("name"), "the single issuer must be the mismatch issuer");
        return assertInstanceOf(Map.class, issuer.get("jwks"), "the issuer must declare a jwks block");
    }

    private static Object loadYaml(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path)) {
            return new Yaml().load(reader);
        }
    }
}
