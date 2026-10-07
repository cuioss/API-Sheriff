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
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.assertApplicationPortAnswers;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.awaitReadiness;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.composeNetwork;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.gatewayLog;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.publishedPort;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.removeContainer;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.startBffGateway;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;
import de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.BffGateway;
import de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.ReadOnlyMount;

import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins, on the native image and against the real identity provider, that
 * {@code oidc.session.ttl_seconds} is an absolute lifetime: a server-mode session ends that long after
 * the login, and a token refresh inside the lifetime does not extend it.
 * <p>
 * <strong>The two instances.</strong> The lifetime is a property of the whole gateway, so the short one
 * needs an instance of its own. The test starts it as a one-off gateway on the compose network and
 * removes it again. Its descriptor is written below {@code target/}: the committed
 * {@code sheriff-config-refresh/gateway.yaml} with {@code ttl_seconds} set to {@value #SHORT_TTL_SECONDS}
 * and the gateway origin retargeted at {@link #ORIGIN} — asserted, on the parsed documents, to be the
 * only two differences. The control is the compose instance {@code api-sheriff-refresh}, which runs the
 * committed descriptor itself, with {@code ttl_seconds: 3600}. Both authenticate as
 * {@code refresh-client}, whose access tokens live 45 seconds, and both refresh inside the last 30
 * seconds of a token.
 * <p>
 * <strong>The timeline.</strong> One user logs in on both instances, a moment apart, and the same calls
 * are then made on both:
 * <ol>
 *   <li>Directly after the login, a mediated call on each relays a bearer.</li>
 *   <li>Once both tokens are inside the refresh window, and before the short lifetime has elapsed, a
 *       mediated call on each relays a <em>different</em> bearer. Both sessions were refreshed; the
 *       short-lived one less than its lifetime after its login.</li>
 *   <li>Once the short lifetime has elapsed since the login completed, and while the refreshed token of
 *       the short-lived session has not expired, that session is refused: {@code 401} for a
 *       non-navigation request, a redirect to the identity provider's authorization endpoint for a
 *       navigation. At the same instant the control session is served.</li>
 * </ol>
 * The control is what attributes the refusal. The two sessions differ in the declared lifetime and in
 * nothing else that the timeline touches — same client, same token lifetime, same refresh at the same
 * age — so a session that ends on one instance and is served on the other ended on the lifetime, not
 * on a token that expired or a refresh that failed.
 * <p>
 * <strong>What this suite does NOT prove.</strong> It does not measure when exactly the session ends:
 * it asserts that it was served before the lifetime had elapsed and refused {@value #PAST_THE_LIFETIME_SECONDS}
 * seconds after, not the instant in between. It does not exercise cookie mode, nor a session that was
 * refreshed more than once, and it asserts nothing about the identity provider's own session. Like
 * every {@code Bff*IT} it replays a cookie map and asserts nothing about browser cookie policy.
 * <p>
 * <strong>Timing.</strong> The waits are wall-clock and deliberate: the property is defined in elapsed
 * time against a declared lifetime, so there is no state to poll for. One rig at a time — the
 * container name and the gateway port are fixed.
 * <p>
 * The suite does not extend {@code BaseIntegrationTest}: no request goes to the primary instance.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class BffSessionAbsoluteTtlIT {

    /** The session route both instances serve, relaying a bearer to the echo upstream. */
    private static final String MEDIATED_PATH = "/bff-session/get";

    private static final String GATEWAY = "sheriff-absolute-ttl";

    /**
     * The fixed host port of the one-off gateway's application listener, published on every interface
     * (see {@link OneOffGatewayContainers#applicationPortPublication(int)}). The compose stack
     * publishes {@code 10443}–{@code 10455}; the other one-off gateways take {@code 10462}–{@code 10468}.
     */
    private static final int APPLICATION_PORT = 10459;

    private static final String ORIGIN = "https://localhost:" + APPLICATION_PORT;

    /** The control: the compose instance that runs the committed descriptor unchanged. */
    private static final String CONTROL_ORIGIN = BffKeycloakLoginFlow.REFRESH_GATEWAY_ORIGIN;

    private static final Path SOURCE_DESCRIPTOR = DOCKER.resolve(Path.of("sheriff-config-refresh", "gateway.yaml"));

    /** Where the short-lifetime descriptor is written; a build output, never committed. */
    private static final Path DERIVED_DESCRIPTOR = Path.of("target", "absolute-ttl", "gateway.yaml");

    /** Readable by the gateway image's own user, which is not the uid the build runs as. */
    private static final String DESCRIPTOR_MODE = "rw-r--r--";

    /** The lifetime the committed descriptor declares, and the control instance therefore runs. */
    private static final int CONTROL_TTL_SECONDS = 3600;

    /** The lifetime under test. Stated here: it is the contract the timeline is laid out against. */
    private static final int SHORT_TTL_SECONDS = 40;

    private static final String TTL_KEY = "ttl_seconds";

    /** The committed descriptor's lifetime line, which the derivation replaces. */
    private static final String SOURCE_TTL_LINE = "    " + TTL_KEY + ": " + CONTROL_TTL_SECONDS + "\n";
    private static final String DERIVED_TTL_LINE = "    " + TTL_KEY + ": " + SHORT_TTL_SECONDS + "\n";

    /** The client-level {@code access.token.lifespan} the realm declares for {@code refresh-client}. */
    private static final Duration ACCESS_TOKEN_LIFESPAN = Duration.ofSeconds(45);

    /**
     * How long after the later of the two logins the refreshing call is made. The refresh window of a
     * 45-second token under a 30-second leeway opens 15 seconds after the token was issued; 20 is past
     * that for both sessions and leaves the short lifetime of {@value #SHORT_TTL_SECONDS} seconds well
     * ahead.
     */
    private static final Duration INTO_THE_REFRESH_WINDOW = Duration.ofSeconds(20);

    /** How far past the end of the short lifetime the refused calls are made. */
    private static final int PAST_THE_LIFETIME_SECONDS = 3;

    private static final String SIGNING_KEYS_MOUNT = "/app/signing-keys";
    private static final String PROOF_KEY_FILE = "dpop-rsa.pem";

    private static final long BOOT_TIMEOUT_SECONDS = 90L;

    @Test
    @DisplayName("a session ends ttl_seconds after its login although it was refreshed in between; the same timeline is still served under a long lifetime")
    void sessionEndsAtItsDeclaredLifetimeWhateverTheRefreshDid() {
        Path descriptor = writeShortLifetimeDescriptor();
        removeContainer(GATEWAY);
        try {
            startShortLifetimeGateway(descriptor);

            Instant loginStarted = Instant.now();
            Session shortLived = login(ORIGIN);
            Instant loginCompleted = Instant.now();
            Session control = login(CONTROL_ORIGIN);
            Instant controlLoginCompleted = Instant.now();
            String shortLivedAtLogin = servedBearer(shortLived, ORIGIN, "the short-lived session, directly after its login");
            String controlAtLogin = servedBearer(control, CONTROL_ORIGIN, "the control session, directly after its login");

            sleepUntil(controlLoginCompleted.plus(INTO_THE_REFRESH_WINDOW));
            String shortLivedRefreshed = servedBearer(shortLived, ORIGIN, "the short-lived session, inside its lifetime");
            Instant refreshedAt = Instant.now();
            String controlRefreshed = servedBearer(control, CONTROL_ORIGIN, "the control session, at the same age");
            assertAll("both sessions were refreshed, the short-lived one inside its lifetime",
                    () -> assertTrue(refreshedAt.isBefore(loginStarted.plusSeconds(SHORT_TTL_SECONDS)),
                            "the refreshing call must have been answered before the short lifetime had elapsed, "
                                    + "otherwise it does not show a refresh inside the lifetime"),
                    () -> assertNotEquals(shortLivedAtLogin, shortLivedRefreshed,
                            "inside the refresh window the short-lived session must relay a rotated bearer"),
                    () -> assertNotEquals(controlAtLogin, controlRefreshed,
                            "inside the refresh window the control session must relay a rotated bearer"));

            sleepUntil(loginCompleted.plusSeconds(SHORT_TTL_SECONDS + (long) PAST_THE_LIFETIME_SECONDS));
            Response refusedCall = call(shortLived, ORIGIN, "application/json");
            Response refusedNavigation = call(shortLived, ORIGIN, "text/html");
            Response controlCall = call(control, CONTROL_ORIGIN, "application/json");
            Instant refusedAt = Instant.now();

            assertAll("the short-lived session has ended; the control session has not",
                    () -> assertTrue(refusedAt.isBefore(refreshedAt.plus(ACCESS_TOKEN_LIFESPAN)),
                            "the refused calls must be made while the refreshed access token has not expired, "
                                    + "otherwise the refusal could be the token's and not the lifetime's"),
                    () -> assertEquals(401, refusedCall.statusCode(), () -> "a non-navigation request past the "
                            + "lifetime must be refused 401. " + gatewayLog(GATEWAY)),
                    () -> assertEquals(302, refusedNavigation.statusCode(), () -> "a navigation past the lifetime "
                            + "must be redirected into a login. " + gatewayLog(GATEWAY)),
                    () -> assertTrue(String.valueOf(refusedNavigation.getHeader("Location"))
                                    .contains("/protocol/openid-connect/auth"),
                            () -> "the navigation must be redirected to the identity provider's authorization "
                                    + "endpoint, was sent to " + refusedNavigation.getHeader("Location")),
                    () -> assertEquals(200, controlCall.statusCode(), "control: under the long lifetime the "
                            + "session of the same age, refreshed at the same age, must still be served"),
                    () -> assertNotNull(controlCall.path("headers.Authorization"),
                            "control: the served call must still relay a bearer"));
        } finally {
            removeContainer(GATEWAY);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Requests
    // ---------------------------------------------------------------------------------------------

    private static Session login(String origin) {
        return BffKeycloakLoginFlow.login(MEDIATED_PATH, origin, BffKeycloakLoginFlow.REFRESH_USERNAME,
                BffKeycloakLoginFlow.REFRESH_PASSWORD);
    }

    private static Response call(Session session, String origin, String accept) {
        return BffKeycloakLoginFlow.gateway(session.gatewayCookies(), origin)
                .header("Accept", accept)
                .redirects().follow(false)
                .when().get(MEDIATED_PATH)
                .then().extract().response();
    }

    /**
     * Makes one mediated call and asserts it was served.
     *
     * @return the relayed {@code Authorization} value, as the echo upstream reports it
     */
    private static String servedBearer(Session session, String origin, String which) {
        Response response = call(session, origin, "application/json");
        assertEquals(200, response.statusCode(), () -> which + " must be served on " + origin);
        return BffEndpointScopesIT.mediatedAuthorization(response);
    }

    // ---------------------------------------------------------------------------------------------
    // The one-off gateway
    // ---------------------------------------------------------------------------------------------

    private static void startShortLifetimeGateway(Path descriptor) {
        startBffGateway(
                new BffGateway(GATEWAY, composeNetwork(), GATEWAY, APPLICATION_PORT, descriptor, "localhost.crt",
                        "localhost.key",
                        List.of("OIDC_CLIENT_SECRET=" + BffTlsRelaxationIT.refreshClientSecret(),
                                "OIDC_DPOP_KEY_FILE=" + SIGNING_KEYS_MOUNT + "/" + PROOF_KEY_FILE)),
                List.of(new ReadOnlyMount(DOCKER.resolve("signing-keys"), SIGNING_KEYS_MOUNT)),
                List.of());
        awaitReadiness(GATEWAY, "https://localhost:" + publishedPort(GATEWAY, 9000),
                response -> response.statusCode() == 200, BOOT_TIMEOUT_SECONDS,
                "the short-lifetime gateway, a container of " + OneOffGatewayContainers.IMAGE
                        + ", to report readiness UP");
        assertApplicationPortAnswers(GATEWAY, ORIGIN);
    }

    /**
     * Writes the short-lifetime descriptor: the committed refresh descriptor with its lifetime set to
     * {@value #SHORT_TTL_SECONDS} seconds and its origin retargeted at {@link #ORIGIN}. The parsed
     * result is asserted to be the parsed source with exactly those two changes applied.
     *
     * @return the derived descriptor
     */
    private static Path writeShortLifetimeDescriptor() {
        try {
            String source = Files.readString(SOURCE_DESCRIPTOR);
            assertAll(SOURCE_DESCRIPTOR + " must hold what the derivation replaces",
                    () -> assertTrue(source.contains(CONTROL_ORIGIN), "the origin " + CONTROL_ORIGIN),
                    () -> assertTrue(source.contains(SOURCE_TTL_LINE), "the line '" + SOURCE_TTL_LINE.strip() + "'"),
                    () -> assertEquals(source.indexOf(SOURCE_TTL_LINE), source.lastIndexOf(SOURCE_TTL_LINE),
                            "that line exactly once"));
            Files.createDirectories(DERIVED_DESCRIPTOR.getParent());
            Files.writeString(DERIVED_DESCRIPTOR,
                    source.replace(SOURCE_TTL_LINE, DERIVED_TTL_LINE).replace(CONTROL_ORIGIN, ORIGIN));
            Files.setPosixFilePermissions(DERIVED_DESCRIPTOR, PosixFilePermissions.fromString(DESCRIPTOR_MODE));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot derive a descriptor from " + SOURCE_DESCRIPTOR, e);
        }

        Map<String, Object> source = loadYaml(SOURCE_DESCRIPTOR);
        Map<String, Object> derived = loadYaml(DERIVED_DESCRIPTOR);
        Object sourceTtl = session(source).get(TTL_KEY);
        Object expected = retargeted(source);
        session(mapping(expected, "the retargeted source")).put(TTL_KEY, SHORT_TTL_SECONDS);
        Map<String, Object> derivedOidc = mapping(derived.get("oidc"), "oidc");
        assertAll("the derived descriptor " + DERIVED_DESCRIPTOR,
                () -> assertEquals(CONTROL_TTL_SECONDS, sourceTtl,
                        "control: the committed descriptor, which the control instance runs, keeps the long lifetime"),
                () -> assertEquals(SHORT_TTL_SECONDS, session(derived).get(TTL_KEY), "oidc.session." + TTL_KEY),
                () -> assertEquals(ORIGIN + "/auth/callback", derivedOidc.get("redirect_uri"), "oidc.redirect_uri"),
                () -> assertEquals(expected, derived,
                        "it must be its source with the lifetime and the gateway origin changed, and nothing else"));
        return DERIVED_DESCRIPTOR;
    }

    /** The {@code oidc.session} block of a parsed descriptor. */
    private static Map<String, Object> session(Map<String, Object> descriptor) {
        return mapping(mapping(descriptor.get("oidc"), "oidc").get("session"), "oidc.session");
    }

    /**
     * A deep copy of a parsed YAML node in which every string names {@link #ORIGIN} where it named
     * {@link #CONTROL_ORIGIN}: what retargeting the gateway origin, and nothing else, makes of it.
     */
    private static Object retargeted(Object node) {
        return switch (node) {
            case Map<?, ?> members -> {
                Map<String, Object> copy = new LinkedHashMap<>();
                members.forEach((key, value) -> copy.put(String.valueOf(key), retargeted(value)));
                yield copy;
            }
            case List<?> elements -> {
                List<Object> copy = new ArrayList<>();
                elements.forEach(element -> copy.add(retargeted(element)));
                yield copy;
            }
            case String text -> text.replace(CONTROL_ORIGIN, ORIGIN);
            case null, default -> node;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object node, String what) {
        return (Map<String, Object>) assertInstanceOf(Map.class, node, () -> what + " must be a mapping");
    }

    private static Map<String, Object> loadYaml(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return mapping(new Yaml().load(in), file.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /**
     * Bounded wall-clock wait. The property is defined in elapsed time against a declared lifetime, so
     * there is no condition to poll for.
     */
    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - the declared session lifetime IS the clock under test
    private static void sleepUntil(Instant instant) {
        long millis = Duration.between(Instant.now(), instant).toMillis();
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting on the session lifetime", interrupted);
        }
    }
}
