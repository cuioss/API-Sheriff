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
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.assembleConfigurationDirectory;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.awaitReadiness;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.composeNetwork;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.gatewayLog;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.publishedPort;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.removeContainer;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.startBffGateway;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.yaml.snakeyaml.Yaml;

import de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.BffGateway;
import de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.ReadOnlyMount;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins, on the native image, that the gateway's reserved OIDC paths win over a proxy route whose
 * prefix contains them, and which pipeline stages a request to a reserved path passes: the baseline
 * security filter that runs before route selection, and none of the stages that belong to a route.
 * <p>
 * <strong>The rig.</strong> One one-off gateway on the compose network, started once for the class and
 * removed afterwards. Its configuration directory is assembled below {@code target/}: a copy of the
 * shared configuration, the fixture {@code reserved-path-overlap/auth-overlap.yaml} as one further
 * endpoint file, and a gateway document derived from
 * {@code sheriff-config-passthrough-empty/gateway.yaml} by adding the one anchor the fixture names —
 * asserted to be the only difference. The directory is mounted whole in place of the shared one. The
 * fixture declares a single route, {@code path_prefix: /auth}, to the echo upstream, with
 * {@code allowed_methods: ["POST"]} and the gateway-wide strict filter profile.
 * <p>
 * <strong>How a request is known not to have reached the upstream.</strong> The route forwards every
 * header that is not gateway-owned, and the upstream echoes what it received. Every request of this
 * suite carries a probe header with a value of its own. The control shows that value in the echo of a
 * routed request; its absence from an answer shows the request reached no upstream.
 * <p>
 * <strong>What this suite proves.</strong>
 * <ul>
 *   <li><em>Control.</em> A {@code POST} to a path below {@code /auth} that is not reserved is
 *       proxied: the upstream echoes the method and the probe.</li>
 *   <li><em>The reserved paths win.</em> For each of the eight reserved paths the descriptor's
 *       {@code oidc} block yields — {@link #RESERVED_PATHS}, asserted against the descriptor — the
 *       same {@code POST} is not echoed. The route would have forwarded it: the control differs from
 *       it in the path alone.</li>
 *   <li><em>The baseline filter applies to a reserved path.</em> One query parameter above the strict
 *       preset's count cap is rejected {@code 400} on the client JWKS path, which answers {@code 200}
 *       without it, and is rejected {@code 400} with the same problem type on the routed path.</li>
 *   <li><em>The route's verb gate does not.</em> A {@code GET} is refused {@code 405} on the routed
 *       path, naming {@code POST} as the allowed verb, and is answered {@code 200} with the key set
 *       on the client JWKS path.</li>
 *   <li><em>The route's post-route parameter checks do not.</em> A doubly encoded parameter value is
 *       rejected {@code 400} on the routed path — where an ordinary value under the same name is
 *       forwarded — and is answered {@code 200} with the key set on the client JWKS path.</li>
 * </ul>
 * The three stage pairs use the client JWKS path as their reserved path because it is the one
 * reserved endpoint that answers an anonymous {@code GET} with {@code 200} and reads no input, so an
 * answer other than the key set is attributable to a pipeline stage and to nothing else.
 * <p>
 * <strong>What this suite does NOT prove.</strong> It says nothing about what each reserved endpoint
 * answers — only that the answer is not the upstream's. It runs on the OIDC host; that six of the
 * eight paths fall through to the route table on any other host is not exercised. It does not
 * exercise the application portal's reserved path, nor a route whose prefix is a reserved path
 * itself.
 * <p>
 * <strong>The committed configuration is not touched.</strong> The fixture is copied into the
 * assembled directory and never bind-mounted into the shared one, which is mounted read-only by every
 * compose instance. The class asserts before it starts and after it has finished that the committed
 * {@code sheriff-config/endpoints/} directory holds no file of the fixture's name.
 * <p>
 * The suite does not extend {@code BaseIntegrationTest}: every request goes to the rig's own gateway.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Reserved OIDC paths below a proxy route's prefix: they win, and only the baseline filter applies to them")
class BffReservedPathPrecedenceIT {

    /**
     * The eight reserved paths of the rig's gateway, as {@code ReservedPathRegistry} derives them from
     * the descriptor's {@code oidc} block: the callback, the logout, its return leg, the back-channel
     * logout receiver, the user-info fold, the login initiation, the step-up entry point and — declared
     * by no key — the default client JWKS path.
     */
    private static final Set<String> RESERVED_PATHS = Set.of("/auth/callback", "/auth/logout",
            "/auth/logout/return", "/auth/backchannel", "/auth/userinfo", "/auth/login", "/auth/step-up",
            "/auth/jwks");

    /** The reserved path no descriptor key declares; the default the gateway publishes its client key at. */
    private static final String CLIENT_JWKS_PATH = "/auth/jwks";

    /** A path below the route's prefix that is not reserved. */
    private static final String ROUTED_PATH = "/auth/overlap-control";

    private static final String GATEWAY = "sheriff-reserved-path-overlap";

    /**
     * The fixed host port of the application listener, published on every interface (see
     * {@link OneOffGatewayContainers#applicationPortPublication(int)}). The compose stack publishes
     * {@code 10443}–{@code 10455}; the other one-off gateways take {@code 10459}, {@code 10462},
     * {@code 10463} and {@code 10465}–{@code 10468}.
     */
    private static final int APPLICATION_PORT = 10464;

    private static final String ORIGIN = "https://localhost:" + APPLICATION_PORT;

    private static final Path SOURCE_DESCRIPTOR =
            DOCKER.resolve(Path.of("sheriff-config-passthrough-empty", "gateway.yaml"));
    private static final Path OVERLAP_ENDPOINT = DOCKER.resolve(Path.of("reserved-path-overlap", "auth-overlap.yaml"));

    /** The committed directory every compose instance loads its endpoints from. */
    private static final Path COMMITTED_ENDPOINTS = DOCKER.resolve(Path.of("sheriff-config", "endpoints"));

    /** Where the rig writes what it derives and assembles; build outputs, never committed. */
    private static final Path WORK_DIRECTORY = Path.of("target", "reserved-path-overlap");
    private static final Path DERIVED_DESCRIPTOR = WORK_DIRECTORY.resolve("gateway.yaml");
    private static final Path ASSEMBLED_CONFIGURATION = WORK_DIRECTORY.resolve("sheriff-config");

    /** Readable by the gateway image's own user, which is not the uid the build runs as. */
    private static final String DESCRIPTOR_MODE = "rw-r--r--";

    private static final String ANCHORS_KEY = "anchors";
    private static final String OVERLAP_ANCHOR = "auth-overlap";

    /** The top-level line of the source descriptor the anchor is inserted after. */
    private static final String ANCHORS_LINE = "\n" + ANCHORS_KEY + ":\n";

    /** The one anchor the derivation adds: the fixture's own, public and disjoint from every other. */
    private static final String OVERLAP_ANCHOR_BLOCK = "  " + OVERLAP_ANCHOR + ":\n"
            + "    path_prefix: /auth\n"
            + "    type: proxy\n"
            + "    access: public\n";

    private static final String SIGNING_KEYS_MOUNT = "/app/signing-keys";

    /** The one variable the source descriptor references; any committed signing key satisfies it. */
    private static final String PROOF_KEY_VARIABLE = "OIDC_DPOP_KEY_FILE";
    private static final String PROOF_KEY_FILE = "dpop-rsa.pem";

    /** A header the gateway does not own, so the fixture's route forwards it and the upstream echoes it. */
    private static final String PROBE_HEADER = "X-Sheriff-Overlap-Probe";

    /** The strict preset's query-parameter count cap, enforced by the baseline filter for every path. */
    private static final int STRICT_PARAMETER_COUNT_CAP = 20;

    /** A parameter value the strict profile's post-route checks refuse: a doubly encoded slash. */
    private static final String REFUSED_PARAMETER_QUERY = "return_to=%252F";

    /** The same parameter with a value those checks admit. */
    private static final String ADMITTED_PARAMETER_QUERY = "return_to=plain";

    private static final long BOOT_TIMEOUT_SECONDS = 90L;

    @BeforeAll
    static void startTheGateway() {
        assertTheCommittedConfigurationHoldsNoFixture("before the rig starts");
        removeContainer(GATEWAY);
        Path configuration = assembleConfigurationDirectory(ASSEMBLED_CONFIGURATION, writeDerivedDescriptor(),
                List.of(OVERLAP_ENDPOINT));
        startBffGateway(
                new BffGateway(GATEWAY, composeNetwork(), GATEWAY, APPLICATION_PORT, configuration, "localhost.crt",
                        "localhost.key", List.of(PROOF_KEY_VARIABLE + "=" + SIGNING_KEYS_MOUNT + "/" + PROOF_KEY_FILE)),
                List.of(new ReadOnlyMount(DOCKER.resolve("signing-keys"), SIGNING_KEYS_MOUNT)),
                List.of());
        awaitReadiness(GATEWAY, "https://localhost:" + publishedPort(GATEWAY, 9000),
                response -> response.statusCode() == 200, BOOT_TIMEOUT_SECONDS,
                "the reserved-path gateway, a container of " + OneOffGatewayContainers.IMAGE
                        + ", to report readiness UP");
        OneOffGatewayContainers.assertApplicationPortAnswers(GATEWAY, ORIGIN);
    }

    @AfterAll
    static void removeTheGateway() {
        removeContainer(GATEWAY);
        assertTheCommittedConfigurationHoldsNoFixture("after the rig has finished");
    }

    @Test
    @DisplayName("control: a path below /auth that is not reserved is proxied and echoed")
    void aPathBelowTheOverlapThatIsNotReservedIsProxied() {
        String probe = probe();

        Response echoed = post(ROUTED_PATH, probe);

        assertAll("the routed path reaches the echo upstream",
                () -> assertEquals(200, echoed.statusCode(), () -> echoed.asString() + " " + gatewayLog(GATEWAY)),
                () -> assertEquals("POST", echoed.path("method"), () -> echoed.asString()),
                () -> assertTrue(echoed.asString().contains(probe),
                        () -> "the upstream must echo the probe header it received: " + echoed.asString()));
    }

    /** The parameter source of {@link #aReservedPathIsNotProxied(String)}: every pinned path, in a stable order. */
    static Stream<String> reservedPaths() {
        return RESERVED_PATHS.stream().sorted();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reservedPaths")
    @DisplayName("a reserved path is answered by the gateway and never reaches the upstream of the overlapping route")
    void aReservedPathIsNotProxied(String reservedPath) {
        String probe = probe();

        Response answered = post(reservedPath, probe);

        assertFalse(answered.asString().contains(probe),
                () -> "a request to " + reservedPath + " was echoed by the upstream of the /auth route (status "
                        + answered.statusCode() + "): the reserved path did not win. " + answered.asString());
    }

    @Test
    @DisplayName("the eight asserted paths are exactly the reserved paths the descriptor's oidc block yields")
    void theAssertedPathsAreTheReservedPathsOfTheDescriptor() {
        Map<String, Object> oidc = mapping(loadYaml(DERIVED_DESCRIPTOR).get("oidc"), "oidc");
        Map<String, Object> logout = mapping(oidc.get("logout"), "oidc.logout");
        Set<String> declared = new LinkedHashSet<>();
        declared.add(pathOf(oidc.get("redirect_uri")));
        declared.add(pathOf(logout.get("path")));
        declared.add(pathOf(logout.get("post_logout_redirect_uri")));
        declared.add(pathOf(logout.get("backchannel_path")));
        declared.add(pathOf(mapping(oidc.get("user_info"), "oidc.user_info").get("path")));
        declared.add(pathOf(mapping(oidc.get("login"), "oidc.login").get("path")));
        declared.add(pathOf(mapping(oidc.get("step_up"), "oidc.step_up").get("path")));
        assertFalse(mapping(oidc.get("client_authentication"), "oidc.client_authentication").containsKey("jwks_path"),
                "the descriptor must declare no jwks_path, so the client key is published at the default path");
        declared.add(CLIENT_JWKS_PATH);

        assertAll("the reserved paths of " + DERIVED_DESCRIPTOR,
                () -> assertEquals(RESERVED_PATHS, declared),
                () -> assertEquals(8, declared.size(), "eight distinct reserved paths"),
                () -> assertTrue(declared.stream().allMatch(path -> path.startsWith("/auth/")),
                        () -> "every reserved path must lie below the prefix of the overlapping route: " + declared));
    }

    @Test
    @DisplayName("the baseline filter rejects a reserved path as it rejects the routed path, with the same problem type")
    void theBaselineFilterAppliesToAReservedPath() {
        String overCap = IntStream.rangeClosed(0, STRICT_PARAMETER_COUNT_CAP)
                .mapToObj(index -> "p" + index + "=v")
                .collect(Collectors.joining("&"));

        Response reservedWithin = get(CLIENT_JWKS_PATH, probe());
        Response reservedOver = get(CLIENT_JWKS_PATH + "?" + overCap, probe());
        Response routedWithin = post(ROUTED_PATH, probe());
        Response routedOver = post(ROUTED_PATH + "?" + overCap, probe());

        assertAll("one query parameter above the strict count cap",
                () -> assertServesTheClientKeySet(reservedWithin, "control: the reserved path without the violation"),
                () -> assertEquals(200, routedWithin.statusCode(),
                        () -> "control: the routed path without the violation. " + routedWithin.asString()),
                () -> assertEquals(400, reservedOver.statusCode(),
                        () -> "the baseline filter must reject the reserved path. " + reservedOver.asString()),
                () -> assertEquals(400, routedOver.statusCode(),
                        () -> "the baseline filter must reject the routed path. " + routedOver.asString()),
                () -> assertNotNull(routedOver.path("type"),
                        () -> "the rejection must carry a problem type: " + routedOver.asString()),
                () -> assertEquals((Object) routedOver.path("type"), reservedOver.path("type"),
                        "the same violation must be the same problem on both paths"));
    }

    @Test
    @DisplayName("the verb gate of the overlapping route refuses the routed path and does not apply to a reserved path")
    void theVerbGateDoesNotApplyToAReservedPath() {
        Response routed = get(ROUTED_PATH, probe());
        Response reserved = get(CLIENT_JWKS_PATH, probe());

        assertAll("a GET, which the overlapping route does not allow",
                () -> assertEquals(405, routed.statusCode(),
                        () -> "the route's verb gate must refuse the routed path. " + routed.asString()),
                () -> assertEquals("POST", routed.getHeader("Allow"), "the refusal names the route's allowed verb"),
                () -> assertServesTheClientKeySet(reserved, "the reserved endpoint answers the same verb"));
    }

    @Test
    @DisplayName("the post-route parameter checks of the overlapping route refuse the routed path and do not apply to a reserved path")
    void thePostRouteParameterChecksDoNotApplyToAReservedPath() {
        Response routedAdmitted = post(ROUTED_PATH + "?" + ADMITTED_PARAMETER_QUERY, probe());
        Response routedRefused = post(ROUTED_PATH + "?" + REFUSED_PARAMETER_QUERY, probe());
        Response reserved = get(CLIENT_JWKS_PATH + "?" + REFUSED_PARAMETER_QUERY, probe());

        assertAll("a parameter value the strict profile refuses after route selection",
                () -> assertEquals(200, routedAdmitted.statusCode(),
                        () -> "control: the parameter itself is forwarded. " + routedAdmitted.asString()),
                () -> assertEquals(400, routedRefused.statusCode(),
                        () -> "the route's parameter checks must refuse the value. " + routedRefused.asString()),
                () -> assertNotEquals(400, reserved.statusCode(),
                        () -> "no parameter check of a route may judge a reserved path. " + reserved.asString()),
                () -> assertServesTheClientKeySet(reserved, "the reserved endpoint answers, whatever the query says"));
    }

    // ---------------------------------------------------------------------------------------------
    // Requests
    // ---------------------------------------------------------------------------------------------

    private static String probe() {
        return UUID.randomUUID().toString();
    }

    /** A request to the rig's gateway carrying the probe; the path and query are sent byte for byte. */
    private static RequestSpecification request(String probe) {
        return given().relaxedHTTPSValidation()
                .baseUri(ORIGIN)
                .basePath("")
                .urlEncodingEnabled(false)
                .redirects().follow(false)
                .header("Accept", "application/json")
                .header(PROBE_HEADER, probe);
    }

    private static Response post(String pathAndQuery, String probe) {
        return request(probe).contentType("text/plain").body("reserved-path-precedence")
                .when().post(pathAndQuery)
                .then().extract().response();
    }

    private static Response get(String pathAndQuery, String probe) {
        return request(probe).when().get(pathAndQuery).then().extract().response();
    }

    /** The answer of the client JWKS endpoint: {@code 200} and a key set holding at least one key. */
    private static void assertServesTheClientKeySet(Response response, String why) {
        assertEquals(200, response.statusCode(), () -> why + " — expected the client key set, was "
                + response.statusCode() + ": " + response.asString() + " " + gatewayLog(GATEWAY));
        List<Object> keys = response.jsonPath().getList("keys");
        assertTrue(keys != null && !keys.isEmpty(), () -> why + " — expected a key set: " + response.asString());
    }

    // ---------------------------------------------------------------------------------------------
    // Configuration
    // ---------------------------------------------------------------------------------------------

    private static void assertTheCommittedConfigurationHoldsNoFixture(String when) {
        Path leaked = COMMITTED_ENDPOINTS.resolve(OVERLAP_ENDPOINT.getFileName().toString());
        assertFalse(Files.exists(leaked), () -> when + ", " + leaked + " exists: the fixture must only ever be "
                + "copied into the configuration directory assembled below target/, never into the committed "
                + "directory every compose instance loads");
    }

    /**
     * Writes the gateway document of the rig: the committed passthrough-empty descriptor with the
     * fixture's anchor added. The result is asserted to differ from its source in that one anchor and
     * in nothing else, and to reference the one variable the rig supplies.
     *
     * @return the derived descriptor
     */
    private static Path writeDerivedDescriptor() {
        try {
            String source = Files.readString(SOURCE_DESCRIPTOR);
            assertEquals(source.indexOf(ANCHORS_LINE), source.lastIndexOf(ANCHORS_LINE),
                    () -> SOURCE_DESCRIPTOR + " must hold exactly one top-level " + ANCHORS_KEY + " line");
            assertTrue(source.contains(ANCHORS_LINE),
                    () -> SOURCE_DESCRIPTOR + " must declare a top-level " + ANCHORS_KEY + " block");
            Files.createDirectories(WORK_DIRECTORY);
            Files.writeString(DERIVED_DESCRIPTOR, source.replace(ANCHORS_LINE, ANCHORS_LINE + OVERLAP_ANCHOR_BLOCK));
            Files.setPosixFilePermissions(DERIVED_DESCRIPTOR, PosixFilePermissions.fromString(DESCRIPTOR_MODE));
            assertTrue(source.contains("${" + PROOF_KEY_VARIABLE + "}"),
                    () -> SOURCE_DESCRIPTOR + " must reference " + PROOF_KEY_VARIABLE + ", the variable the rig supplies");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot derive a descriptor from " + SOURCE_DESCRIPTOR, e);
        }

        Map<String, Object> expected = loadYaml(SOURCE_DESCRIPTOR);
        Map<String, Object> derived = loadYaml(DERIVED_DESCRIPTOR);
        Map<String, Object> derivedAnchors = new LinkedHashMap<>(mapping(derived.get(ANCHORS_KEY), ANCHORS_KEY));
        Object added = derivedAnchors.remove(OVERLAP_ANCHOR);
        derived.put(ANCHORS_KEY, derivedAnchors);
        assertAll("the derived descriptor " + DERIVED_DESCRIPTOR,
                () -> assertEquals(Map.of("path_prefix", "/auth", "type", "proxy", "access", "public"), added,
                        "it must declare the fixture's anchor"),
                () -> assertEquals(expected, derived, "apart from that anchor it must be its source, unchanged"));
        return DERIVED_DESCRIPTOR;
    }

    /** The path component of a configured value: a full URL contributes its path, a bare path itself. */
    private static String pathOf(Object configured) {
        String value = assertInstanceOf(String.class, configured, "a reserved path must be configured");
        return value.contains("://") ? URI.create(value).getPath() : value;
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
}
