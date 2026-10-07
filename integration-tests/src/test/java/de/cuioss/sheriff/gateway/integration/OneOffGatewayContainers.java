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
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import io.restassured.response.Response;

/**
 * The shared harness for integration tests that boot a gateway as a one-off {@code docker run}
 * container instead of as a compose service — among them the gateways whose readiness is {@code DOWN}
 * by design for some or all of their life, which the compose readiness gate in
 * {@code start-integration-container.sh} would refuse.
 * <p>
 * It carries the docker process plumbing, the port and network lookups, the readiness and log polls,
 * the bearer-gated asset probe and the token mint, so each such test states only its own topology and
 * legs. Every gateway it starts runs the {@value #IMAGE} image the compose stack runs. It starts two
 * shapes of gateway:
 * <ul>
 *   <li>{@link #startGateway(String, String, Path)} — a bearer-only gateway over one standalone
 *       {@code gateway.yaml} beside exactly one shared endpoint file
 *       ({@code sheriff-config/endpoints/assets-secure.yaml}) and no {@code topology.properties}, with
 *       both ports on ephemeral loopback ports so a run cannot collide with the live stack;</li>
 *   <li>{@link #startBffGateway(BffGateway)} — a BFF gateway over the <em>shared</em>
 *       {@code sheriff-config} directory with one overlay descriptor, exactly as a compose variant
 *       instance is mounted. Its application listener is published on a fixed loopback port, because
 *       the descriptor of a BFF names its own origin; its management port stays ephemeral.
 *       {@link #startBffGatewayWithSigningKeys(BffGateway)} is the same gateway with the stack's
 *       signing-key directory mounted as well.</li>
 * </ul>
 * <p>
 * A rig of its own — a gateway beside a stub or a proxy on a dedicated docker network — starts the
 * second shape through {@link #startBffGateway(BffGateway, List, List)}, which adds read-only bind
 * mounts, a configuration directory assembled below {@code target/} by
 * {@link #assembleConfigurationDirectory(Path, Path, List)} and process arguments, and builds its
 * surroundings with the network and auxiliary-container helpers.
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

    /**
     * The bearer-gated directory asset every bearer-only one-off gateway fetches; served from the
     * mounted assets.
     */
    static final String SECURE_ASSET = "/secure-assets/app.css";

    /** WARN — a load attempt produced no key set and a retry was scheduled. */
    static final String RETRY_SCHEDULED_RECORD = "ApiSheriff-129";

    /** The configuration directory every compose gateway mounts at {@code /app/sheriff-config}. */
    private static final Path SHERIFF_CONFIG = DOCKER.resolve("sheriff-config");

    /** The module's build directory; the only place a configuration directory may be assembled. */
    private static final Path BUILD_DIRECTORY = Path.of("target");

    private static final String DESCRIPTOR_FILE = "gateway.yaml";
    private static final String ENDPOINTS_DIRECTORY = "endpoints";

    private static final String CONFIGURATION_MOUNT = "/app/sheriff-config";
    private static final String CERTIFICATES_MOUNT = "/app/certificates";
    private static final String ASSETS_MOUNT = "/app/assets";
    private static final String DEMO_MOUNT = "/app/demo";

    /** The container paths a one-off BFF gateway mounts itself; no extra mount may reach into one. */
    private static final List<String> HARNESS_MOUNTS =
            List.of(CONFIGURATION_MOUNT, CERTIFICATES_MOUNT, ASSETS_MOUNT, DEMO_MOUNT);

    private static final Path ASSETS_SECURE_ENDPOINT =
            SHERIFF_CONFIG.resolve(Path.of("endpoints", "assets-secure.yaml"));
    private static final Path ASSETS = DOCKER.resolve("assets");

    /** The signing-key directory every compose gateway mounts at {@code /app/signing-keys}. */
    private static final Path SIGNING_KEYS = DOCKER.resolve("signing-keys");

    /** The demo SPA the {@code /assets/demo} route of the shared configuration serves, as compose mounts it. */
    private static final Path DEMO_SPA = Path.of("..", "demo-client", "src", "main", "resources", "spa");

    /** The host-published Keycloak origin the test JVM can reach (compose {@code 1443 -> 8443}). */
    private static final String KEYCLOAK_ORIGIN = "https://localhost:1443";

    /** The compose service whose network a one-off container joins to reach the real Keycloak. */
    private static final String KEYCLOAK_SERVICE = "keycloak";

    private static final String READINESS_CHECK = "gateway-readiness";

    private static final long POLL_INTERVAL_MILLIS = 500L;

    /** Upper bound on one readiness request, connection and answer each. */
    private static final int READINESS_REQUEST_TIMEOUT_SECONDS = 5;

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
     * <p>
     * The descriptor must be readable by others before the container starts, and this is asserted
     * rather than assumed: the image runs as its own non-root user, whose uid differs from the build's,
     * and the descriptor is bind-mounted as a single file, so a mode readable by its owner only makes
     * the gateway refuse its own configuration at boot. Without the assertion that surfaces only as an
     * opaque "no public port published" once the exited container is probed.
     *
     * @param gateway    the unique container name
     * @param network    the docker network the container joins
     * @param descriptor the standalone {@code gateway.yaml} mounted as the gateway's global document;
     *                   must carry {@link PosixFilePermission#OTHERS_READ}, since the gateway image
     *                   reads it as a different uid than the build that wrote or checked it out
     */
    static void startGateway(String gateway, String network, Path descriptor) {
        assertReadableByTheGatewayUser(descriptor);
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
     * What distinguishes one one-off BFF gateway from a compose variant instance.
     *
     * @param name                the unique container name
     * @param network             the docker network the container joins — the compose network, so the
     *                            topology aliases of the shared configuration and Keycloak resolve
     * @param networkAlias        the name other containers of that network reach the gateway under
     * @param applicationHostPort the fixed loopback host port the application listener is published on;
     *                            the gateway origin its descriptor names
     * @param descriptor          the overlay {@code gateway.yaml}; must carry
     *                            {@link PosixFilePermission#OTHERS_READ}
     * @param certificate         the file name, in the certificates directory, of the server
     *                            certificate the application listener presents
     * @param certificateKey      the file name of that certificate's private key
     * @param environment         further environment entries, in {@code NAME=value} form
     */
    record BffGateway(String name, String network, String networkAlias, int applicationHostPort, Path descriptor,
    String certificate, String certificateKey, List<String> environment) {
    }

    /**
     * Starts a one-off BFF gateway. It mounts the shared
     * {@code sheriff-config} directory with one overlay descriptor, the certificates, the assets and
     * the demo directory. Nothing else is mounted — in particular no signing-key directory, so the
     * container holds no signing-key file a descriptor could name.
     * <p>
     * No trust argument follows the image: the OIDC back-channel trusts Keycloak, which serves the
     * stack's self-signed certificate, through the {@code benchmark-idp} profile the descriptor names as
     * {@code egress_tls.oidc_tls_profile} and {@code QUARKUS_CONFIG_LOCATIONS} binds to the stack's
     * trust store.
     * <p>
     * The management listener keeps the stack certificate; only the application listener presents the
     * pair the caller names, which is the one another container verifies when it dials the gateway
     * under {@link BffGateway#networkAlias()}.
     *
     * @param gateway what to start
     */
    static void startBffGateway(BffGateway gateway) {
        startBffGateway(gateway, false);
    }

    /**
     * Starts a one-off BFF gateway exactly as {@link #startBffGateway(BffGateway)} does, and
     * additionally mounts the stack's signing-key directory at {@code /app/signing-keys}, as every
     * compose gateway instance has it.
     * <p>
     * This is the shape of a gateway whose descriptor is derived from a compose instance's descriptor
     * and keeps that instance's client: it names the client-authentication key file and the DPoP proof
     * key file the compose instance names, so both must be present. Keycloak verifies the client
     * assertion of such a gateway against the key set the primary {@code api-sheriff} instance
     * publishes, which is the public half of the very same client-authentication key file.
     *
     * @param gateway what to start
     */
    static void startBffGatewayWithSigningKeys(BffGateway gateway) {
        startBffGateway(gateway, true);
    }

    private static void startBffGateway(BffGateway gateway, boolean mountSigningKeys) {
        assertReadableByTheGatewayUser(gateway.descriptor());
        List<String> arguments = new ArrayList<>(List.of("run", "-d",
                "--name", gateway.name(),
                "--network", gateway.network(),
                "--network-alias", gateway.networkAlias(),
                "-p", "127.0.0.1:" + gateway.applicationHostPort() + ":8443",
                "-p", "127.0.0.1::9000",
                "-e", "QUARKUS_PROFILE=it",
                "-e", "QUARKUS_CONFIG_LOCATIONS=/app/certificates/benchmark-idp-trust.properties",
                "-e", "QUARKUS_HTTP_SSL_CERTIFICATE_FILES=/app/certificates/" + gateway.certificate(),
                "-e", "QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES=/app/certificates/" + gateway.certificateKey(),
                "-e", "QUARKUS_MANAGEMENT_SSL_CERTIFICATE_FILES=/app/certificates/localhost.crt",
                "-e", "QUARKUS_MANAGEMENT_SSL_CERTIFICATE_KEY_FILES=/app/certificates/localhost.key",
                "-e", "SHERIFF_CONFIG_DIR=/app/sheriff-config"));
        for (String entry : gateway.environment()) {
            arguments.add("-e");
            arguments.add(entry);
        }
        if (mountSigningKeys) {
            arguments.addAll(List.of("-v", SIGNING_KEYS.toAbsolutePath() + ":/app/signing-keys:ro"));
        }
        arguments.addAll(List.of(
                "-v", CERTIFICATES.toAbsolutePath() + ":/app/certificates:ro",
                "-v", SHERIFF_CONFIG.toAbsolutePath() + ":/app/sheriff-config:ro",
                "-v", gateway.descriptor().toAbsolutePath() + ":/app/sheriff-config/gateway.yaml:ro",
                "-v", ASSETS.toAbsolutePath() + ":/app/assets:ro",
                "-v", DEMO_SPA.toAbsolutePath().normalize() + ":/app/demo:ro",
                IMAGE));
        docker("start the one-off BFF gateway " + gateway.name(), arguments.toArray(String[]::new));
    }

    /**
     * One additional read-only bind mount of a one-off BFF gateway: a host directory or file the base
     * start does not mount, such as the signing-key directory.
     *
     * @param hostPath      the host path mounted
     * @param containerPath the absolute path it appears under inside the container; must lie outside
     *                      every path the harness itself mounts — see
     *                      {@link #assertOutsideTheHarnessMounts(String)}
     */
    record ReadOnlyMount(Path hostPath, String containerPath) {
    }

    /**
     * Starts a one-off BFF gateway like {@link #startBffGateway(BffGateway)}, with what a dedicated rig
     * adds to it.
     * <p>
     * <strong>The configuration.</strong> {@link BffGateway#descriptor()} names either a single overlay
     * descriptor, mounted over the shared {@code sheriff-config} directory exactly as the base start
     * does, or a complete configuration <em>directory</em> assembled by
     * {@link #assembleConfigurationDirectory(Path, Path, List)}. Such a directory is mounted whole at
     * {@code /app/sheriff-config}, read-only, <em>in place of</em> the shared directory and its overlay —
     * so the container has no mount point inside the configuration directory and the committed
     * directory is not a mount source at all.
     * <p>
     * <strong>The extra mounts.</strong> Each is bind-mounted read-only. Before any docker call, a mount
     * whose container path is, or lies below, a path the harness itself mounts is refused: the
     * configuration directory is mounted read-only, so the container runtime could not create a mount
     * point for a name that does not exist in it, and under a writable parent it would leave an empty
     * file behind in the committed directory, which every compose instance would then load. A rig that
     * needs a further endpoint file assembles a configuration directory instead.
     * <p>
     * <strong>The process arguments.</strong> They follow the image name verbatim, as the caller
     * supplies them; this harness neither adds to nor interprets them.
     *
     * @param gateway          what to start
     * @param extraMounts      the additional read-only bind mounts, possibly empty
     * @param processArguments the arguments handed to the gateway process, possibly empty
     */
    static void startBffGateway(BffGateway gateway, List<ReadOnlyMount> extraMounts, List<String> processArguments) {
        List<String> arguments = bffGatewayRunArguments(gateway, extraMounts, processArguments);
        docker("start the one-off BFF gateway " + gateway.name(), arguments.toArray(String[]::new));
    }

    /**
     * Assembles — without running anything — the complete {@code docker run} argument list
     * {@link #startBffGateway(BffGateway, List, List)} executes, after every refusal that start owes:
     * the configuration must be readable by the gateway user, and no extra mount may reach into a
     * path the harness mounts. It is a function of its own so those refusals can be exercised without
     * a docker daemon.
     *
     * @param gateway          what to start
     * @param extraMounts      the additional read-only bind mounts
     * @param processArguments the arguments appended after the image name
     * @return the docker arguments, beginning with {@code run}
     */
    static List<String> bffGatewayRunArguments(BffGateway gateway, List<ReadOnlyMount> extraMounts,
            List<String> processArguments) {
        extraMounts.forEach(mount -> assertOutsideTheHarnessMounts(mount.containerPath()));
        boolean assembledDirectory = Files.isDirectory(gateway.descriptor());
        if (assembledDirectory) {
            assertTreeReadableByTheGatewayUser(gateway.descriptor());
        } else {
            assertReadableByTheGatewayUser(gateway.descriptor());
        }
        List<String> arguments = new ArrayList<>(List.of("run", "-d",
                "--name", gateway.name(),
                "--network", gateway.network(),
                "--network-alias", gateway.networkAlias(),
                "-p", "127.0.0.1:" + gateway.applicationHostPort() + ":8443",
                "-p", "127.0.0.1::9000",
                "-e", "QUARKUS_PROFILE=it",
                "-e", "QUARKUS_CONFIG_LOCATIONS=/app/certificates/benchmark-idp-trust.properties",
                "-e", "QUARKUS_HTTP_SSL_CERTIFICATE_FILES=/app/certificates/" + gateway.certificate(),
                "-e", "QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES=/app/certificates/" + gateway.certificateKey(),
                "-e", "QUARKUS_MANAGEMENT_SSL_CERTIFICATE_FILES=/app/certificates/localhost.crt",
                "-e", "QUARKUS_MANAGEMENT_SSL_CERTIFICATE_KEY_FILES=/app/certificates/localhost.key",
                "-e", "SHERIFF_CONFIG_DIR=" + CONFIGURATION_MOUNT));
        for (String entry : gateway.environment()) {
            arguments.add("-e");
            arguments.add(entry);
        }
        arguments.addAll(List.of("-v", CERTIFICATES.toAbsolutePath() + ":" + CERTIFICATES_MOUNT + ":ro"));
        if (assembledDirectory) {
            arguments.addAll(List.of(
                    "-v", gateway.descriptor().toAbsolutePath().normalize() + ":" + CONFIGURATION_MOUNT + ":ro"));
        } else {
            arguments.addAll(List.of(
                    "-v", SHERIFF_CONFIG.toAbsolutePath() + ":" + CONFIGURATION_MOUNT + ":ro",
                    "-v", gateway.descriptor().toAbsolutePath() + ":" + CONFIGURATION_MOUNT + "/gateway.yaml:ro"));
        }
        arguments.addAll(List.of(
                "-v", ASSETS.toAbsolutePath() + ":" + ASSETS_MOUNT + ":ro",
                "-v", DEMO_SPA.toAbsolutePath().normalize() + ":" + DEMO_MOUNT + ":ro"));
        for (ReadOnlyMount mount : extraMounts) {
            arguments.add("-v");
            arguments.add(mount.hostPath().toAbsolutePath().normalize() + ":" + mount.containerPath() + ":ro");
        }
        arguments.add(IMAGE);
        arguments.addAll(processArguments);
        return arguments;
    }

    /**
     * Refuses a container path that is, or lies below, a path the harness itself mounts into a one-off
     * BFF gateway ({@code /app/sheriff-config}, {@code /app/certificates}, {@code /app/assets},
     * {@code /app/demo}), naming the path. The comparison is by path element after normalisation, so
     * {@code /app/assets-extra} is outside {@code /app/assets} while {@code /app/x/../assets/a} is not.
     *
     * @param containerPath the container path of an extra mount; must be absolute
     */
    static void assertOutsideTheHarnessMounts(String containerPath) {
        Path requested = Path.of(containerPath).normalize();
        assertTrue(requested.isAbsolute(),
                () -> "the extra mount container path '" + containerPath + "' is not absolute");
        for (String harnessMount : HARNESS_MOUNTS) {
            assertFalse(requested.startsWith(harnessMount),
                    () -> "the extra mount container path '" + containerPath + "' is, or lies below, " + harnessMount
                            + ", which the harness itself mounts read-only: the container runtime cannot create a "
                            + "mount point inside a read-only mount, and under a writable one it would leave an empty "
                            + "file in the committed directory. Assemble a configuration directory instead.");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Assembled configuration directory
    // ---------------------------------------------------------------------------------------------

    /**
     * Assembles a complete gateway configuration directory below the module's {@code target/}
     * directory, for a rig that needs an endpoint file the shared configuration does not hold.
     * <p>
     * A previous assembly at {@code destination} is cleared first. The shared {@code sheriff-config}
     * tree is then copied, {@code descriptor} is written as the copy's {@code gateway.yaml}, and each
     * extra file is copied into its {@code endpoints/} under its own file name. Finally every directory
     * of the copy is made readable and traversable, and every file readable, by others — and that is
     * asserted, since the gateway image reads the directory as a different uid than this build.
     * <p>
     * The committed {@code sheriff-config} directory is read only; nothing is written to it.
     *
     * @param destination        where to assemble; must lie strictly below the module's {@code target/}
     * @param descriptor         the gateway document of the assembled configuration
     * @param extraEndpointFiles further endpoint files, copied into {@code endpoints/}
     * @return {@code destination}, absolute and normalised
     */
    static Path assembleConfigurationDirectory(Path destination, Path descriptor, List<Path> extraEndpointFiles) {
        Path buildDirectory = BUILD_DIRECTORY.toAbsolutePath().normalize();
        Path assembled = destination.toAbsolutePath().normalize();
        assertTrue(assembled.startsWith(buildDirectory) && !assembled.equals(buildDirectory),
                () -> "the configuration directory " + assembled + " must be assembled below " + buildDirectory
                        + ": anything else risks writing into a committed fixture directory");
        try {
            deleteRecursively(assembled);
            copyTree(SHERIFF_CONFIG, assembled);
            Files.copy(descriptor, assembled.resolve(DESCRIPTOR_FILE), StandardCopyOption.REPLACE_EXISTING);
            Path endpoints = Files.createDirectories(assembled.resolve(ENDPOINTS_DIRECTORY));
            for (Path extra : extraEndpointFiles) {
                Files.copy(extra, endpoints.resolve(extra.getFileName().toString()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            makeReadableByOthers(assembled);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot assemble the configuration directory " + assembled, e);
        }
        assertTreeReadableByTheGatewayUser(assembled);
        return assembled;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (Files.notExists(root)) {
            return;
        }
        try (Stream<Path> entries = Files.walk(root)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> entries = Files.walk(source)) {
            for (Path entry : entries.toList()) {
                Path copy = target.resolve(source.relativize(entry).toString());
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(copy);
                } else {
                    Files.copy(entry, copy, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void makeReadableByOthers(Path root) throws IOException {
        try (Stream<Path> entries = Files.walk(root)) {
            for (Path entry : entries.toList()) {
                Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
                permissions.addAll(Files.getPosixFilePermissions(entry));
                permissions.add(PosixFilePermission.OTHERS_READ);
                if (Files.isDirectory(entry)) {
                    permissions.add(PosixFilePermission.OTHERS_EXECUTE);
                }
                Files.setPosixFilePermissions(entry, permissions);
            }
        }
    }

    /**
     * Fails fast when any entry of a configuration directory about to be mounted is not readable by
     * others — a directory must also be traversable — naming the entry.
     *
     * @param root the configuration directory the one-off gateway will mount
     */
    private static void assertTreeReadableByTheGatewayUser(Path root) {
        List<Path> entries;
        try (Stream<Path> walk = Files.walk(root)) {
            entries = walk.toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list the configuration directory " + root.toAbsolutePath(), e);
        }
        for (Path entry : entries) {
            assertReadableByTheGatewayUser(entry);
            if (Files.isDirectory(entry)) {
                assertTrue(posixPermissions(entry).contains(PosixFilePermission.OTHERS_EXECUTE),
                        () -> "the configuration directory entry " + entry.toAbsolutePath()
                                + " is not traversable by others, so the " + IMAGE
                                + " image, which runs as a different uid than this build, could not list it");
            }
        }
    }

    private static Set<PosixFilePermission> posixPermissions(Path entry) {
        try {
            return Files.getPosixFilePermissions(entry);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the file mode of " + entry.toAbsolutePath(), e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Dedicated networks and auxiliary containers
    // ---------------------------------------------------------------------------------------------

    /**
     * Creates a dedicated docker network for one rig. Network names are global to the docker daemon,
     * so the caller gives it a unique name and removes it with {@link #removeNetwork(String)}.
     *
     * @param network the unique network name
     */
    static void createNetwork(String network) {
        docker("create the docker network " + network, "network", "create", network);
    }

    /**
     * Removes a dedicated docker network on teardown. Every container must have left it first; a
     * failure is returned rather than raised, so it cannot mask the outcome of the test it served.
     *
     * @param network the network name
     * @return the docker output, or a marker naming the failure
     */
    static String removeNetwork(String network) {
        return dockerQuietly("network", "rm", network);
    }

    /**
     * Connects an already running container to a further network, where the other containers of that
     * network reach it under {@code alias}.
     *
     * @param network   the network to join
     * @param container the running container
     * @param alias     the name it is reachable under on that network
     */
    static void connectToNetwork(String network, String container, String alias) {
        docker("connect " + container + " to the docker network " + network, "network", "connect",
                "--alias", alias, network, container);
    }

    /**
     * Disconnects a container from a network on teardown; a failure is returned rather than raised.
     *
     * @param network   the network to leave
     * @param container the container
     * @return the docker output, or a marker naming the failure
     */
    static String disconnectFromNetwork(String network, String container) {
        return dockerQuietly("network", "disconnect", network, container);
    }

    /**
     * Starts a named auxiliary container — a stub or a proxy a rig places beside its gateway — detached,
     * from an arbitrary image.
     *
     * @param name       the unique container name
     * @param image      the image reference
     * @param runOptions the {@code docker run} options placed before the image (network, alias, published
     *                   ports, mounts, environment), verbatim
     * @param command    the arguments placed after the image, verbatim; possibly empty
     */
    static void startAuxiliaryContainer(String name, String image, List<String> runOptions, List<String> command) {
        List<String> arguments = new ArrayList<>(List.of("run", "-d", "--name", name));
        arguments.addAll(runOptions);
        arguments.add(image);
        arguments.addAll(command);
        docker("start the auxiliary container " + name + " from " + image, arguments.toArray(String[]::new));
    }

    /**
     * Force-removes a container on teardown, running or not; a failure is returned rather than raised.
     *
     * @param container the container name
     * @return the docker output, or a marker naming the failure
     */
    static String removeContainer(String container) {
        return dockerQuietly("rm", "-f", container);
    }

    /**
     * Restarts a one-off gateway and reads its management port again. Docker assigns an ephemeral host
     * port anew when a container starts, so the port published before the restart is not the one the
     * restarted gateway answers on; a fixed host port stays as it was.
     *
     * @param gateway the container name
     * @return the loopback host port the management interface is published on after the restart
     */
    static String restartGateway(String gateway) {
        docker("restart the one-off gateway " + gateway, "restart", gateway);
        return publishedPort(gateway, 9000);
    }

    /**
     * Fails fast when a descriptor about to be bind-mounted is not readable by others, naming the file
     * and the cause instead of letting the gateway exit at boot.
     *
     * @param descriptor the descriptor the one-off gateway will mount
     */
    private static void assertReadableByTheGatewayUser(Path descriptor) {
        Set<PosixFilePermission> permissions;
        try {
            permissions = Files.getPosixFilePermissions(descriptor);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the file mode of the one-off gateway descriptor "
                    + descriptor.toAbsolutePath(), e);
        }
        assertTrue(permissions.contains(PosixFilePermission.OTHERS_READ),
                () -> "the one-off gateway descriptor " + descriptor.toAbsolutePath() + " has mode "
                        + PosixFilePermissions.toString(permissions) + ", which is not readable by others: the "
                        + IMAGE + " image runs as a different uid than this build, so the gateway could not read "
                        + "its own configuration and would exit at boot. Make the descriptor world-readable "
                        + "after writing it.");
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
    private static Response readiness(String managementOrigin) throws IOException {
        // Bounded per request, so the poll's own deadline is reached even when the port accepts the
        // connection and then stays silent: an abandoned read is an IOException like every other
        // "not answering yet".
        return given()
                .config(BffKeycloakLoginFlow.clientDeadlines(READINESS_REQUEST_TIMEOUT_SECONDS,
                        READINESS_REQUEST_TIMEOUT_SECONDS))
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
     * Mints an access token from the compose Keycloak's {@code integration} realm, with the password
     * grant of the realm's token-mint client — the one client that allows direct access grants. The
     * realm pins its frontend URL, so the token's {@code iss} is the container-internal issuer
     * ({@code https://keycloak:8443/realms/integration}) the one-off descriptors declare, whichever
     * origin the token was minted through.
     *
     * @return the access token
     */
    static String mintIntegrationRealmToken() {
        String token = given().relaxedHTTPSValidation()
                .contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "password")
                .formParam("client_id", BearerValidationIT.TOKEN_MINT_CLIENT_ID)
                .formParam("client_secret", BearerValidationIT.TOKEN_MINT_CLIENT_SECRET)
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
    private record DockerRun(int exitCode, String output) {
    }

    /**
     * Runs one docker command, waiting for it before reading its output — which is redirected to a
     * file so a large output (a container log) can never fill a pipe and deadlock the wait.
     *
     * @param arguments the docker arguments
     * @return the exit status and merged output
     */
    private static DockerRun runDocker(String... arguments) {
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
