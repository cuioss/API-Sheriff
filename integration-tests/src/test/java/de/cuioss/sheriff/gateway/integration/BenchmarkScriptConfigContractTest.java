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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.cuioss.sheriff.gateway.config.PolicyBlock;
import de.cuioss.sheriff.gateway.config.RouteTableBuilder;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.yaml.snakeyaml.Yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Fast, no-Docker <em>surefire</em> contract that binds every file of the k6 benchmark script
 * directory to the mounted gateway configuration keys it depends on, so a key changed without the
 * script that relies on it turns this build red and names that script.
 * <p>
 * <strong>Why a surefire contract here, and not a check in {@code benchmark-manifest.py}.</strong>
 * {@code benchmarks/scripts/benchmark-manifest.py} runs only inside {@code -Pbenchmark}, and
 * {@code .github/workflows/benchmark.yml} fires that lane after the merge. A check there would report
 * a broken script after the change is already on {@code main}, and the only reaction left at that
 * point is a skip — the non-gating reaction ADR-0075 rejects. This module's surefire suite runs on
 * every pull request, owns the mounted {@code sheriff-config/} it reads, and already reads the k6
 * directory in {@link BenchmarkRealmCredentialConsistencyTest}, the precedent this class follows: no
 * new dependency (SnakeYAML is already on the test path), no container, no network.
 * <p>
 * <strong>Population.</strong> The table's population is every regular file under
 * {@code benchmarks/src/main/resources/k6-scripts/}, walked recursively with NO extension filter: the
 * k6 scripts, the {@code lib/} modules, the shell pre-flight and the proto descriptor the gRPC
 * aspect loads. A file of any type added to the directory needs a row before this class goes green,
 * so the walk cannot be sidestepped by a file type a filter forgot. The shell
 * {@code pre-benchmark-health-check.sh} is in the population on purpose — its data-plane probe
 * composes the same {@code /proxy/static} route {@code proxied_static.js} drives.
 * <p>
 * <strong>Rows.</strong> Each {@link Binding} names the script, how its literal is extracted from the
 * script text, the configuration file, and how the key is resolved there. Each binding asserts BOTH
 * sides: the literal is still present in the script, and it still resolves against the
 * configuration. A {@link ZeroKey} row states why a file depends on no mounted key, so its exclusion
 * is recorded rather than silent.
 * <p>
 * <strong>Route selection is global.</strong> The gateway's route table holds the routes of every
 * enabled endpoint file at once (ADR-0007), so a route-bound row selects the serving route over all
 * of them, the way the gateway does: exact {@code match.path} routes first, then the longest
 * covering {@code match.path_prefix}, and only a route whose {@code match.methods}, when declared,
 * admit the method the script sends. The configuration file a row names is the file it
 * <em>expects</em> to serve the path; a winning route in any other file fails the row, naming the
 * script. A first candidate that also selects on {@code match.host} or {@code match.headers} fails it
 * too, because this guard does not know the script's host and headers.
 * <p>
 * <strong>Discovery leg.</strong> Every call a JavaScript file of the population makes to
 * {@code targetUrl}, {@code wsUrl} or {@code passthroughEmptyUrl} is read from the script text, and
 * its route literal must be covered by a binding of that script, so a new script dependency cannot be
 * added without a row. A route literal is a single-quoted string, a double-quoted string, or a
 * template literal without a substitution. Every other argument — a concatenation, a template
 * substitution, a bare identifier, any other expression — is <em>refused</em> naming the script and
 * the call, never skipped: this leg does not evaluate expressions, so a call it cannot read fails the
 * build until the route is passed as a literal. The leg reads only the {@code .js} files, because the
 * resolvers are JavaScript functions; the shell pre-flight names its probed route through its own
 * binding. The resolvers' declarations in {@code lib/target.js} are not calls.
 * <p>
 * <strong>This test must never pass quietly</strong> (ADR-0030). The walked population is asserted
 * non-empty and fully covered as a property — never as equality with a file count; the discovered
 * literal set is asserted non-empty; every configuration file the table reads is asserted to exist
 * and parse; and the {@link Controls} drive the same resolvers over in-memory configuration
 * specimens that must be rejected, and one that must be accepted.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("k6 benchmark scripts vs the mounted gateway configuration — key-to-script contract")
class BenchmarkScriptConfigContractTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final Path K6_SCRIPTS =
            MODULE.resolve("../benchmarks/src/main/resources/k6-scripts").normalize();

    private static final String CONFIG_DIR = "src/main/docker/sheriff-config/";
    private static final String ENDPOINTS = CONFIG_DIR + "endpoints/";
    private static final String GATEWAY = CONFIG_DIR + "gateway.yaml";
    private static final String TOPOLOGY = CONFIG_DIR + "topology.properties";
    private static final String HTTPBIN = ENDPOINTS + "httpbin.yaml";
    private static final String SECURE = ENDPOINTS + "secure.yaml";
    private static final String WEBSOCKET = ENDPOINTS + "websocket.yaml";
    private static final String GRPC = ENDPOINTS + "grpc.yaml";
    private static final String BFF_SESSION = ENDPOINTS + "bff-session.yaml";
    private static final String COMPOSE_BENCHMARK = "docker-compose.benchmark.yml";

    /**
     * Every mounted configuration file a resolver below reads, relative to the module root: the
     * files the rows name, plus every endpoint document of the mounted configuration, because the
     * gateway selects a route over the routes of all of them at once.
     */
    private static final List<String> CONFIG_FILES = mountedConfigFiles(
            List.of(GATEWAY, TOPOLOGY, HTTPBIN, SECURE, WEBSOCKET, GRPC, BFF_SESSION, COMPOSE_BENCHMARK));

    /**
     * The gateway's route-table order (RouteTableBuilder): exact {@code match.path} routes before
     * {@code match.path_prefix} routes, then the longer ordering key first, then the ordering key
     * lexically; the file breaks the remaining tie so the order is deterministic.
     */
    private static final Comparator<RouteHit> ROUTE_ORDER = Comparator
            .comparing((RouteHit hit) -> !hit.exact())
            .thenComparing(Comparator.comparingInt((RouteHit hit) -> hit.orderingKey().length()).reversed())
            .thenComparing(RouteHit::orderingKey)
            .thenComparing(RouteHit::file);

    private static final String ROUTE = "data-plane route";
    private static final String UPSTREAM = "benchmark upstream repoint";
    private static final String ALPN = "ALPN protocol";
    private static final String SNI = "passthrough SNI host";
    private static final String ISSUER = "token issuer realm";
    private static final String SCOPES = "requested token scopes";
    private static final String BODY_CAP = "request body size";
    private static final String ORIGIN = "WebSocket Origin";
    private static final String GRPC_SERVICES = "gRPC service paths";
    private static final String LOGIN = "BFF login path";

    private static final String GET = "GET";
    private static final String POST = "POST";
    private static final String ANY_PROTOCOL = "";

    /** The route an aspect script drives, read from its {@code TARGET_URL} declaration. */
    private static final String TARGET_ROUTE =
            "const TARGET_URL = __ENV\\.TARGET_URL \\|\\| (?:targetUrl|wsUrl)\\('([^']+)'\\)";
    private static final Pattern TARGET_ROUTE_PATTERN = Pattern.compile(TARGET_ROUTE);

    /**
     * A call of one of the three target resolvers of {@code lib/target.js}, up to its opening
     * parenthesis; the argument is read by {@link #argumentOf} rather than by this pattern, so no
     * argument form can make the call invisible.
     */
    private static final Pattern RESOLVER_CALL = Pattern.compile("\\b(targetUrl|wsUrl|passthroughEmptyUrl)\\s*\\(");

    /** The text in front of a resolver name that makes it the function's declaration, not a call. */
    private static final Pattern DECLARATION_PREFIX = Pattern.compile("\\bfunction\\s+$");

    /**
     * A whole resolver argument that is a route literal: a single- or double-quoted string, or a
     * template literal without a substitution. A string carrying an escape is not matched and so is
     * refused rather than guessed at.
     */
    private static final Pattern ROUTE_LITERAL =
            Pattern.compile("'([^'\\\\\\n]*)'|\"([^\"\\\\\\n]*)\"|`((?:[^`\\\\$]|\\$(?!\\{))*)`");

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*");

    private static final Pattern PROTO_SERVICE = Pattern.compile("(?m)^service\\s+(\\w+)\\s*\\{");

    /** The ADR-0004 Amendment A1 in-file placeholder form an overlay can repoint. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(\\w+)(?::-[^}]*)?}");

    /** The ALPN protocol identifier behind each protocol name k6 reports on a response. */
    private static final Map<String, String> ALPN_IDS = Map.of("HTTP/2.0", "h2", "HTTP/1.1", "http/1.1");

    private static final String ROUTE_RESOLUTION =
            "the route the gateway selects over the routes of every enabled endpoint file - an exact "
                    + "match.path first, else the longest covering match.path_prefix, admitting the method the "
                    + "script sends - must be a route of this file";
    private static final String BODY_CAP_RESOLUTION =
            "the max_body_bytes of the nearest security_filter block of route, endpoint and anchor - which "
                    + "replaces every broader block wholesale - or, when that block declares none, the body cap of "
                    + "its profile's preset, above the body size";
    private static final String UPSTREAM_RESOLUTION =
            "the serving endpoint's base_url alias is a ${VAR:-default} placeholder in topology.properties, "
                    + "and " + COMPOSE_BENCHMARK + " sets VAR on the instance to a service it declares";

    /** The key-to-script table: every file of the k6 directory, with its keys or a zero-key reason. */
    private static final List<Row> TABLE = List.of(
            bind("proxied_static.js", ROUTE, TARGET_ROUTE, HTTPBIN, ROUTE_RESOLUTION,
                    routeResolver(HTTPBIN, GET, ANY_PROTOCOL)),
            bind("proxied_static.js", UPSTREAM, TARGET_ROUTE, TOPOLOGY, UPSTREAM_RESOLUTION,
                    upstreamRepointed(HTTPBIN, GET, "api-sheriff")),

            bind("http2.js", ROUTE, TARGET_ROUTE, HTTPBIN, ROUTE_RESOLUTION,
                    routeResolver(HTTPBIN, GET, ANY_PROTOCOL)),
            bind("http2.js", ALPN, "const EXPECTED_PROTO = '([^']+)'", GATEWAY,
                    "YAML key path tls.alpn containing the ALPN id of the negotiated protocol", alpn()),
            bind("http2.js", UPSTREAM, TARGET_ROUTE, TOPOLOGY, UPSTREAM_RESOLUTION,
                    upstreamRepointed(HTTPBIN, GET, "api-sheriff")),

            bind("passthrough_relay.js", SNI,
                    "const PASSTHROUGH_TARGET_URL = __ENV\\.PASSTHROUGH_TARGET_URL \\|\\| 'https://([^:/']+)",
                    GATEWAY, "YAML key path tls.passthrough_sni.<host>, its alias declared in topology.properties",
                    passthroughSni()),
            bind("passthrough_relay.js", ROUTE, "passthroughEmptyUrl\\('([^']+)'\\)", HTTPBIN, ROUTE_RESOLUTION,
                    routeResolver(HTTPBIN, GET, ANY_PROTOCOL)),
            bind("passthrough_relay.js", UPSTREAM, "passthroughEmptyUrl\\('([^']+)'\\)", TOPOLOGY,
                    UPSTREAM_RESOLUTION, upstreamRepointed(HTTPBIN, GET, "api-sheriff-passthrough-empty")),

            bind("bearer_proxied.js", ROUTE, TARGET_ROUTE, SECURE, ROUTE_RESOLUTION,
                    routeResolver(SECURE, GET, ANY_PROTOCOL)),
            bind("bearer_proxied.js", ISSUER,
                    "const KEYCLOAK_TOKEN_URL = __ENV\\.KEYCLOAK_TOKEN_URL\\s*\\|\\|\\s*'[^']*/realms/([^/']+)/",
                    GATEWAY, "YAML key path token_validation.issuers[].issuer ending in /realms/<realm>", issuer()),
            bind("bearer_proxied.js", SCOPES, "\\bscope: '([^']+)'", GATEWAY,
                    "YAML key path oidc.scopes, every member requested by the script", oidcScopes()),
            bind("bearer_proxied.js", UPSTREAM, TARGET_ROUTE, TOPOLOGY, UPSTREAM_RESOLUTION,
                    upstreamRepointed(SECURE, GET, "api-sheriff")),

            bind("graphql.js", ROUTE, TARGET_ROUTE, HTTPBIN, ROUTE_RESOLUTION,
                    routeResolver(HTTPBIN, POST, ANY_PROTOCOL)),
            bind("graphql.js", UPSTREAM, TARGET_ROUTE, TOPOLOGY, UPSTREAM_RESOLUTION,
                    upstreamRepointed(HTTPBIN, POST, "api-sheriff")),

            bind("upload_small.js", ROUTE, TARGET_ROUTE, HTTPBIN, ROUTE_RESOLUTION,
                    routeResolver(HTTPBIN, POST, ANY_PROTOCOL)),
            bind("upload_small.js", BODY_CAP, "const BODY_BYTES = ([0-9.eE+]+);", GATEWAY, BODY_CAP_RESOLUTION,
                    bodyCapAbove(HTTPBIN, POST)),
            bind("upload_small.js", UPSTREAM, TARGET_ROUTE, TOPOLOGY, UPSTREAM_RESOLUTION,
                    upstreamRepointed(HTTPBIN, POST, "api-sheriff")),

            bind("upload_large.js", ROUTE, TARGET_ROUTE, HTTPBIN, ROUTE_RESOLUTION,
                    routeResolver(HTTPBIN, POST, ANY_PROTOCOL)),
            bind("upload_large.js", BODY_CAP, "const BODY_BYTES = ([0-9.eE+]+);", GATEWAY, BODY_CAP_RESOLUTION,
                    bodyCapAbove(HTTPBIN, POST)),
            bind("upload_large.js", UPSTREAM, TARGET_ROUTE, TOPOLOGY, UPSTREAM_RESOLUTION,
                    upstreamRepointed(HTTPBIN, POST, "api-sheriff")),

            bind("websocket_echo.js", ROUTE, TARGET_ROUTE, WEBSOCKET, ROUTE_RESOLUTION + ", protocol websocket",
                    routeResolver(WEBSOCKET, GET, "websocket")),
            bind("websocket_echo.js", ORIGIN, "const ORIGIN = __ENV\\.WS_ORIGIN \\|\\| '([^']+)'", WEBSOCKET,
                    "websocket.allowed_origins of the route serving the script's TARGET_URL",
                    wsOrigin(WEBSOCKET, GET)),

            bind("grpc_unary.js", ROUTE, "const SERVICE_METHOD = '([^']+)'", GRPC,
                    "the bare gRPC method path /<service>/<method> under a protocol grpc route", grpcMethod(GRPC)),

            bind("echo.proto", GRPC_SERVICES, "(?m)^package\\s+([\\w.]+)\\s*;", GRPC,
                    "every declared service, as the bare path /<package>.<service>, under a protocol grpc route",
                    protoServices(GRPC)),

            bind("session_mediated.js", ROUTE, TARGET_ROUTE, BFF_SESSION, ROUTE_RESOLUTION,
                    routeResolver(BFF_SESSION, GET, ANY_PROTOCOL)),
            bind("session_mediated.js", LOGIN, "const LOGIN_URL = __ENV\\.LOGIN_URL \\|\\| targetUrl\\('([^']+)'\\)",
                    GATEWAY, "YAML key path oidc.login.path, exact match", oidcLoginPath()),
            bind("session_mediated.js", UPSTREAM, TARGET_ROUTE, TOPOLOGY, UPSTREAM_RESOLUTION,
                    upstreamRepointed(BFF_SESSION, GET, "api-sheriff")),

            bind("pre-benchmark-health-check.sh", ROUTE,
                    "\\$\\{INTEGRATION_SERVICE_URL}\\$\\{APPLICATION_ROOT_PATH}(/[^\"]*)\"", HTTPBIN,
                    ROUTE_RESOLUTION, routeResolver(HTTPBIN, GET, ANY_PROTOCOL)),

            new ZeroKey("lib/target.js", "resolves compose service names, ports and the Quarkus management "
                    + "context path from __ENV; it names no key of the mounted configuration"),
            new ZeroKey("lib/summary.js", "the shared handleSummary helper reads only the BENCHMARK_* run "
                    + "knobs from __ENV and gatewayTarget() from lib/target.js"),
            new ZeroKey("gateway_health.js", "drives the Quarkus management interface through managementUrl, "
                    + "which no mounted gateway key addresses"),
            new ZeroKey("health_live.js", "drives the Quarkus management interface through managementUrl, "
                    + "which no mounted gateway key addresses"));

    @Test
    @DisplayName("every file the k6 directory walk finds has a row, and every row names a file the walk finds")
    void everyWalkedFileHasARowAndNoRowIsStale() throws Exception {
        SortedSet<String> walked = walkedPopulation();
        Set<String> tabled = TABLE.stream().map(Row::script).collect(Collectors.toCollection(TreeSet::new));

        SortedSet<String> unrowed = new TreeSet<>(walked);
        unrowed.removeAll(tabled);
        SortedSet<String> stale = new TreeSet<>(tabled);
        stale.removeAll(walked);

        assertAll(
                () -> assertFalse(walked.isEmpty(),
                        () -> "the walk found no regular file under " + K6_SCRIPTS + ", so every row below "
                                + "would be checked against nothing"),
                () -> assertTrue(TABLE.stream().anyMatch(Binding.class::isInstance),
                        "the table declares no binding, so no script is bound to any configuration key"),
                () -> assertTrue(unrowed.isEmpty(),
                        () -> "these files of the k6 directory have no row — bind each to the mounted "
                                + "configuration keys it depends on, or add a zero-key row stating why it "
                                + "depends on none: " + unrowed),
                () -> assertTrue(stale.isEmpty(),
                        () -> "these rows name files the k6 directory walk no longer finds: " + stale),
                () -> assertAll(TABLE.stream()
                        .filter(ZeroKey.class::isInstance)
                        .map(ZeroKey.class::cast)
                        .map(row -> () -> assertFalse(row.reason().isBlank(),
                                () -> "the zero-key row for " + row.script() + " states no reason"))));
    }

    @TestFactory
    @DisplayName("every binding: the literal is in the script and it resolves against the mounted configuration")
    Stream<DynamicTest> everyBindingResolvesAgainstTheMountedConfiguration() throws Exception {
        MountedConfig config = MountedConfig.parse(diskSources());

        return bindings().map(binding -> DynamicTest.dynamicTest(binding.toString(), () -> {
            Optional<String> failure = failureOf(binding, scriptText(binding.script()), config);

            assertFalse(failure.isPresent(), () -> failure.orElse(""));
        }));
    }

    @Test
    @DisplayName("every route literal a script hands to a target resolver is discovered and covered by a binding")
    void everyDiscoveredRouteLiteralIsCoveredByABinding() throws Exception {
        Map<String, List<String>> discovered = new LinkedHashMap<>();
        List<String> refused = new ArrayList<>();
        for (String script : javaScriptPopulation()) {
            Discovery discovery = discover(script, scriptText(script));
            if (!discovery.literals().isEmpty()) {
                discovered.put(script, discovery.literals());
            }
            refused.addAll(discovery.refusals());
        }

        List<String> uncovered = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : discovered.entrySet()) {
            String text = scriptText(entry.getKey());
            Set<String> bound = bindings()
                    .filter(binding -> binding.script().equals(entry.getKey()))
                    .map(binding -> firstGroup(binding.extraction(), text))
                    .flatMap(Optional::stream)
                    .collect(Collectors.toSet());
            entry.getValue().stream()
                    .filter(literal -> !bound.contains(literal))
                    .forEach(literal -> uncovered.add(entry.getKey() + " -> '" + literal + "'"));
        }

        assertAll(
                () -> assertFalse(discovered.isEmpty(),
                        () -> "no targetUrl / wsUrl / passthroughEmptyUrl route literal was discovered under "
                                + K6_SCRIPTS + ", so the discovery leg would check nothing"),
                () -> assertTrue(refused.isEmpty(),
                        () -> "these target resolver calls carry an argument the discovery leg cannot read as a "
                                + "route literal, so their configuration key could never be bound — pass the "
                                + "route as a quoted literal: " + refused),
                () -> assertTrue(uncovered.isEmpty(),
                        () -> "these route literals are handed to a target resolver but no binding of their "
                                + "script extracts them, so their configuration key is unguarded: " + uncovered));
    }

    @Test
    @DisplayName("every configuration file the table reads exists and parses, and every row names one of them")
    void everyConfigurationFileExistsAndParses() throws Exception {
        Map<String, String> sources = diskSources();

        MountedConfig config = MountedConfig.parse(sources);

        assertAll(
                () -> assertEquals(CONFIG_FILES.size(), config.documents().size(),
                        "every configuration file the table reads must parse into one document"),
                () -> assertAll(bindings().map(binding -> () -> assertTrue(CONFIG_FILES.contains(binding.configFile()),
                        () -> "the row " + binding + " names a configuration file this class does not load"))));
    }

    /**
     * ADR-0030 controls: the resolvers above, driven over in-memory configuration specimens. Each
     * rejection specimen changes ONE key a script depends on and must be refused naming that script;
     * the acceptance specimen reshapes a document without breaking the key and must pass, so the
     * resolvers are shown to judge the key rather than the committed file's exact text.
     */
    @Nested
    @DisplayName("controls over in-memory configuration specimens")
    class Controls {

        /** The /upload/small route with its prefix renamed and nothing else changed. */
        private static final String RENAMED_UPLOAD_PREFIX = """
                endpoint:
                  id: httpbin
                  enabled: true
                  base_url: UPSTREAM
                  anchor: api
                  routes:
                    - id: httpbin-upload-small
                      anchor: upload
                      match:
                        path_prefix: /upload/tiny
                        methods: ["POST"]
                """;

        /** The /upload/small route re-declared under another id and with an unrelated sibling route. */
        private static final String RESHAPED_UPLOAD_ROUTE = """
                endpoint:
                  id: httpbin-reshaped
                  enabled: true
                  base_url: UPSTREAM
                  anchor: api
                  routes:
                    - id: some-other-route
                      match:
                        path_prefix: /proxy/elsewhere
                    - id: upload-small-reshaped
                      anchor: upload
                      match:
                        path_prefix: /upload/small
                        methods: ["PUT", "POST"]
                """;

        /**
         * A second endpoint document whose longer prefix covers /proxy/static, so the global route
         * table selects it over httpbin.yaml's /proxy route.
         */
        private static final String SHADOWING_STATIC = """
                endpoint:
                  id: static-shadow
                  enabled: true
                  base_url: WS_UPSTREAM
                  anchor: api
                  auth:
                    require: none
                  routes:
                    - id: static-shadow
                      match:
                        path_prefix: /proxy/static
                """;

        /** A second endpoint document whose only route covers no path a script drives. */
        private static final String UNRELATED_ENDPOINT = """
                endpoint:
                  id: unrelated
                  enabled: true
                  base_url: UPSTREAM
                  anchor: api
                  auth:
                    require: none
                  routes:
                    - id: unrelated-route
                      match:
                        path_prefix: /unrelated/elsewhere
                """;

        /**
         * An endpoint-level security_filter capping bodies at 1024 bytes, and an /upload/small route
         * that declares no block of its own, so the endpoint block is the effective one.
         */
        private static final String ENDPOINT_BODY_CAP = """
                endpoint:
                  id: httpbin
                  enabled: true
                  base_url: UPSTREAM
                  anchor: api
                  auth:
                    require: none
                  security_filter:
                    max_body_bytes: 1024
                  routes:
                    - id: httpbin-upload-small
                      anchor: upload
                      match:
                        path_prefix: /upload/small
                        methods: ["POST"]
                """;

        /**
         * An /upload/large route declaring a security_filter with a profile and no max_body_bytes:
         * the block replaces the upload anchor's wholesale, so the anchor's raised cap is not in
         * effect and the strict preset's body cap is.
         */
        private static final String PROFILE_ONLY_ROUTE_BLOCK = """
                endpoint:
                  id: httpbin
                  enabled: true
                  base_url: UPSTREAM
                  anchor: api
                  auth:
                    require: none
                  routes:
                    - id: httpbin-upload-large
                      anchor: upload
                      match:
                        path_prefix: /upload/large
                        methods: ["POST"]
                      security_filter:
                        profile: strict
                """;

        /**
         * The 1024-byte endpoint block again, under an /upload/small route that declares its own
         * block above the body size, so the route inherits nothing from the endpoint.
         */
        private static final String ROUTE_BODY_CAP_OVER_ENDPOINT = """
                endpoint:
                  id: httpbin
                  enabled: true
                  base_url: UPSTREAM
                  anchor: api
                  auth:
                    require: none
                  security_filter:
                    max_body_bytes: 1024
                  routes:
                    - id: httpbin-upload-small
                      anchor: upload
                      match:
                        path_prefix: /upload/small
                        methods: ["POST"]
                      security_filter:
                        max_body_bytes: 2000000
                """;

        /**
         * An endpoint-level allowed_methods admitting POST only, over the method-less /proxy route
         * that serves the GET of proxied_static.js.
         */
        private static final String ENDPOINT_POST_ONLY = """
                endpoint:
                  id: httpbin
                  enabled: true
                  base_url: UPSTREAM
                  anchor: api
                  auth:
                    require: none
                  allowed_methods: ["POST"]
                  routes:
                    - id: httpbin-proxy
                      match:
                        path_prefix: /proxy
                """;

        /** The same route under an endpoint-level allowed_methods that admits GET. */
        private static final String ENDPOINT_ALLOWS_GET = """
                endpoint:
                  id: httpbin
                  enabled: true
                  base_url: UPSTREAM
                  anchor: api
                  auth:
                    require: none
                  allowed_methods: ["GET", "POST"]
                  routes:
                    - id: httpbin-proxy
                      match:
                        path_prefix: /proxy
                """;

        /** The passthrough SNI map with its only host renamed. */
        private static final String RENAMED_SNI_HOST = """
                tls:
                  passthrough_sni:
                    renamed.test.example: PASSTHROUGH_BACKEND
                """;

        /** The /ws/echo route with its Origin allowlist dropped and the route itself intact. */
        private static final String DROPPED_ORIGIN = """
                endpoint:
                  id: websocket
                  enabled: true
                  base_url: WS_UPSTREAM
                  anchor: ws
                  auth:
                    require: none
                  routes:
                    - id: ws-echo
                      protocol: websocket
                      match:
                        path_prefix: /ws/echo
                      websocket:
                        idle_timeout_seconds: 30
                """;

        @Test
        @DisplayName("a renamed route prefix is rejected, naming the script that must follow")
        void aRenamedRoutePrefixIsRejected() throws Exception {
            MountedConfig specimen = specimenConfig(HTTPBIN, RENAMED_UPLOAD_PREFIX);

            Optional<String> failure = failureOf(binding("upload_small.js", ROUTE), scriptText("upload_small.js"),
                    specimen);

            assertTrue(failure.isPresent(), "a renamed /upload/small prefix must be rejected");
            assertTrue(failure.orElseThrow().startsWith("upload_small.js "),
                    () -> "the rejection must name the script that must follow: " + failure.orElseThrow());
        }

        @Test
        @DisplayName("a renamed passthrough SNI host is rejected, naming the script that must follow")
        void aRenamedSniHostIsRejected() throws Exception {
            MountedConfig specimen = specimenConfig(GATEWAY, RENAMED_SNI_HOST);

            Optional<String> failure = failureOf(binding("passthrough_relay.js", SNI),
                    scriptText("passthrough_relay.js"), specimen);

            assertTrue(failure.isPresent(), "a renamed passthrough_sni host must be rejected");
            assertTrue(failure.orElseThrow().startsWith("passthrough_relay.js "),
                    () -> "the rejection must name the script that must follow: " + failure.orElseThrow());
        }

        @Test
        @DisplayName("a dropped WebSocket Origin is rejected while the route it rides still resolves")
        void aDroppedOriginIsRejected() throws Exception {
            MountedConfig specimen = specimenConfig(WEBSOCKET, DROPPED_ORIGIN);
            String script = scriptText("websocket_echo.js");

            Optional<String> originFailure = failureOf(binding("websocket_echo.js", ORIGIN), script, specimen);
            Optional<String> routeFailure = failureOf(binding("websocket_echo.js", ROUTE), script, specimen);

            assertTrue(originFailure.isPresent(), "an /ws/echo route that no longer admits the Origin must be rejected");
            assertTrue(originFailure.orElseThrow().startsWith("websocket_echo.js "),
                    () -> "the rejection must name the script that must follow: " + originFailure.orElseThrow());
            assertFalse(routeFailure.isPresent(),
                    () -> "the specimen keeps the route, so the rejection must come from the Origin alone: "
                            + routeFailure.orElse(""));
        }

        @Test
        @DisplayName("a reshaped document that keeps the key is accepted")
        void aReshapedDocumentThatKeepsTheKeyIsAccepted() throws Exception {
            MountedConfig specimen = specimenConfig(HTTPBIN, RESHAPED_UPLOAD_ROUTE);
            String script = scriptText("upload_small.js");

            Optional<String> routeFailure = failureOf(binding("upload_small.js", ROUTE), script, specimen);
            Optional<String> capFailure = failureOf(binding("upload_small.js", BODY_CAP), script, specimen);
            Optional<String> upstreamFailure = failureOf(binding("upload_small.js", UPSTREAM), script, specimen);

            assertAll(
                    () -> assertFalse(routeFailure.isPresent(), () -> routeFailure.orElse("")),
                    () -> assertFalse(capFailure.isPresent(), () -> capFailure.orElse("")),
                    () -> assertFalse(upstreamFailure.isPresent(), () -> upstreamFailure.orElse("")));
        }

        @Test
        @DisplayName("a longer prefix in another endpoint file takes over the script's path and is rejected")
        void aLongerPrefixInAnotherEndpointFileIsRejected() throws Exception {
            MountedConfig specimen = specimenConfig(CONFIG_DIR + "endpoints/static-shadow.yaml", SHADOWING_STATIC);
            String script = scriptText("proxied_static.js");

            Optional<String> routeFailure = failureOf(binding("proxied_static.js", ROUTE), script, specimen);
            Optional<String> upstreamFailure = failureOf(binding("proxied_static.js", UPSTREAM), script, specimen);

            assertAll(
                    () -> assertTrue(routeFailure.isPresent(),
                            "a /proxy/static prefix declared in another endpoint file must take over the path"),
                    () -> assertTrue(routeFailure.orElse("").startsWith("proxied_static.js "),
                            () -> "the rejection must name the script that must follow: " + routeFailure.orElse("")),
                    () -> assertTrue(upstreamFailure.isPresent(),
                            "the upstream of the route that actually serves /proxy/static is not the one bound"));
        }

        @Test
        @DisplayName("an unrelated second endpoint file changes nothing and the binding is accepted")
        void anUnrelatedSecondEndpointFileIsAccepted() throws Exception {
            MountedConfig specimen = specimenConfig(CONFIG_DIR + "endpoints/unrelated.yaml", UNRELATED_ENDPOINT);
            String script = scriptText("proxied_static.js");

            Optional<String> routeFailure = failureOf(binding("proxied_static.js", ROUTE), script, specimen);
            Optional<String> upstreamFailure = failureOf(binding("proxied_static.js", UPSTREAM), script, specimen);

            assertAll(
                    () -> assertFalse(routeFailure.isPresent(), () -> routeFailure.orElse("")),
                    () -> assertFalse(upstreamFailure.isPresent(), () -> upstreamFailure.orElse("")));
        }

        @Test
        @DisplayName("an endpoint-level body cap under a route with no block of its own is rejected")
        void anEndpointBodyCapIsRejected() throws Exception {
            MountedConfig specimen = specimenConfig(HTTPBIN, ENDPOINT_BODY_CAP);

            Optional<String> failure = failureOf(binding("upload_small.js", BODY_CAP), scriptText("upload_small.js"),
                    specimen);

            assertTrue(failure.isPresent(), "the endpoint's 1024-byte cap is the effective one and must be rejected");
            assertTrue(failure.orElseThrow().startsWith("upload_small.js "),
                    () -> "the rejection must name the script that must follow: " + failure.orElseThrow());
        }

        @Test
        @DisplayName("a route block declaring only a profile replaces the anchor's cap wholesale and is rejected")
        void aProfileOnlyRouteBlockIsRejected() throws Exception {
            MountedConfig specimen = specimenConfig(HTTPBIN, PROFILE_ONLY_ROUTE_BLOCK);

            Optional<String> failure = failureOf(binding("upload_large.js", BODY_CAP), scriptText("upload_large.js"),
                    specimen);

            assertTrue(failure.isPresent(),
                    "the route's block replaces the upload anchor's raised cap, so the profile's cap is in effect");
            assertTrue(failure.orElseThrow().startsWith("upload_large.js "),
                    () -> "the rejection must name the script that must follow: " + failure.orElseThrow());
        }

        @Test
        @DisplayName("a route block of its own above the body size is accepted whatever the endpoint declares")
        void aRouteBodyCapOverAnEndpointCapIsAccepted() throws Exception {
            MountedConfig specimen = specimenConfig(HTTPBIN, ROUTE_BODY_CAP_OVER_ENDPOINT);

            Optional<String> failure = failureOf(binding("upload_small.js", BODY_CAP), scriptText("upload_small.js"),
                    specimen);

            assertFalse(failure.isPresent(), () -> failure.orElse(""));
        }

        @Test
        @DisplayName("an endpoint-level allowed_methods that refuses the script's method is rejected")
        void anEndpointAllowedMethodsRefusingTheMethodIsRejected() throws Exception {
            MountedConfig specimen = specimenConfig(HTTPBIN, ENDPOINT_POST_ONLY);

            Optional<String> failure = failureOf(binding("proxied_static.js", ROUTE),
                    scriptText("proxied_static.js"), specimen);

            assertTrue(failure.isPresent(), "the endpoint's allowed_methods [POST] refuses the script's GET with 405");
            assertTrue(failure.orElseThrow().startsWith("proxied_static.js "),
                    () -> "the rejection must name the script that must follow: " + failure.orElseThrow());
        }

        @Test
        @DisplayName("an endpoint-level allowed_methods that admits the script's method is accepted")
        void anEndpointAllowedMethodsAdmittingTheMethodIsAccepted() throws Exception {
            MountedConfig specimen = specimenConfig(HTTPBIN, ENDPOINT_ALLOWS_GET);

            Optional<String> failure = failureOf(binding("proxied_static.js", ROUTE),
                    scriptText("proxied_static.js"), specimen);

            assertFalse(failure.isPresent(), () -> failure.orElse(""));
        }

        @Test
        @DisplayName("a double-quoted resolver argument is discovered as a route literal")
        void aDoubleQuotedResolverArgumentIsDiscovered() {
            Discovery discovery = discover("specimen.js", "const TARGET = targetUrl(\"/proxy/new\");\n");

            assertAll(
                    () -> assertEquals(List.of("/proxy/new"), discovery.literals()),
                    () -> assertTrue(discovery.refusals().isEmpty(), () -> String.valueOf(discovery.refusals())));
        }

        @Test
        @DisplayName("a template literal without substitutions is discovered as a route literal")
        void aTemplateLiteralResolverArgumentIsDiscovered() {
            Discovery discovery = discover("specimen.js", "const TARGET = wsUrl(`/ws/new`);\n");

            assertAll(
                    () -> assertEquals(List.of("/ws/new"), discovery.literals()),
                    () -> assertTrue(discovery.refusals().isEmpty(), () -> String.valueOf(discovery.refusals())));
        }

        @Test
        @DisplayName("a concatenated resolver argument is refused, naming the script and the call")
        void aConcatenatedResolverArgumentIsRefused() {
            Discovery discovery = discover("specimen.js", "const TARGET = targetUrl('/proxy/' + name);\n");

            assertRefusedNaming(discovery, "targetUrl('/proxy/' + name)");
        }

        @Test
        @DisplayName("a variable resolver argument is refused, naming the script and the call")
        void aVariableResolverArgumentIsRefused() {
            Discovery discovery = discover("specimen.js", "const TARGET = passthroughEmptyUrl(path);\n");

            assertRefusedNaming(discovery, "passthroughEmptyUrl(path)");
        }

        @Test
        @DisplayName("a resolver declaration is neither discovered nor refused")
        void aResolverDeclarationIsNotACall() {
            Discovery discovery = discover("specimen.js", "export function targetUrl(path) {\n    return path;\n}\n");

            assertAll(
                    () -> assertTrue(discovery.literals().isEmpty(), () -> String.valueOf(discovery.literals())),
                    () -> assertTrue(discovery.refusals().isEmpty(), () -> String.valueOf(discovery.refusals())));
        }

        private static void assertRefusedNaming(Discovery discovery, String call) {
            assertAll(
                    () -> assertTrue(discovery.literals().isEmpty(), () -> String.valueOf(discovery.literals())),
                    () -> assertEquals(1, discovery.refusals().size(),
                            () -> "the call " + call + " must be refused exactly once: " + discovery.refusals()),
                    () -> assertTrue(discovery.refusals().stream()
                            .anyMatch(refusal -> refusal.startsWith("specimen.js ") && refusal.contains(call)),
                            () -> "the refusal must name the script and the call " + call + ": "
                                    + discovery.refusals()));
        }
    }

    /**
     * Checks one binding: the literal must still be extractable from the script, and it must still
     * resolve against the configuration.
     *
     * @param binding    the row to check
     * @param scriptText the full text of the row's script
     * @param config     the parsed configuration to resolve against
     * @return the failure, naming the script that must follow, or empty when both sides hold
     */
    private static Optional<String> failureOf(Binding binding, String scriptText, MountedConfig config) {
        Optional<String> literal = firstGroup(binding.extraction(), scriptText);
        if (literal.isEmpty()) {
            return Optional.of(binding.script() + " no longer carries the literal this row extracts for its "
                    + binding.key() + " (" + binding.extraction().pattern() + ") — re-bind the row to what the "
                    + "script depends on now");
        }
        return binding.resolver().unresolved(config, scriptText, literal.get())
                .map(reason -> binding.script() + " must follow: it depends on '" + literal.get() + "' ("
                        + binding.key() + "), which no longer resolves in " + binding.configFile() + " by "
                        + binding.resolution() + ": " + reason);
    }

    /**
     * Reads every target resolver call of one script. A call whose argument is a route literal
     * contributes that literal; every other call — a concatenation, a template substitution, a bare
     * identifier, any other expression, or an argument list that never closes — is refused by name,
     * never skipped, because a call this leg cannot read is a dependency no row can be required for.
     * The resolvers' own declarations in {@code lib/target.js} are not calls and contribute nothing.
     *
     * @param script the file the text was read from, named in every refusal
     * @param text   the script text
     * @return the discovered literals and the refused calls
     */
    private static Discovery discover(String script, String text) {
        List<String> literals = new ArrayList<>();
        List<String> refusals = new ArrayList<>();
        Matcher call = RESOLVER_CALL.matcher(text);
        while (call.find()) {
            int lineStart = text.lastIndexOf('\n', call.start()) + 1;
            boolean declaration = DECLARATION_PREFIX.matcher(text.substring(lineStart, call.start())).find();
            if (!declaration) {
                readCall(script, call.group(1), argumentOf(text, call.end()), literals, refusals);
            }
        }
        return new Discovery(List.copyOf(literals), List.copyOf(refusals));
    }

    private static void readCall(String script, String resolver, Optional<String> argument, List<String> literals,
            List<String> refusals) {
        if (argument.isEmpty()) {
            refusals.add(script + " calls " + resolver + "( with an argument list that never closes, which the "
                    + "discovery leg cannot read as a route literal");
            return;
        }
        String raw = argument.get();
        Matcher literal = ROUTE_LITERAL.matcher(raw.strip());
        if (literal.matches()) {
            literals.add(Stream.of(literal.group(1), literal.group(2), literal.group(3))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElseThrow());
            return;
        }
        refusals.add(script + " calls " + resolver + "(" + raw + ") with " + unreadableForm(raw.strip())
                + ", which the discovery leg cannot read as a route literal");
    }

    /** Names why a resolver argument is not a route literal. */
    private static String unreadableForm(String argument) {
        if (argument.isEmpty()) {
            return "no argument";
        }
        if (argument.startsWith("`") && argument.contains("${")) {
            return "a template literal carrying a substitution";
        }
        if (IDENTIFIER.matcher(argument).matches()) {
            return "the bare identifier " + argument;
        }
        if (argument.contains("+")) {
            return "a concatenation";
        }
        return "an expression";
    }

    /**
     * The raw argument text of a call, from just after its opening parenthesis to the parenthesis
     * that closes it, skipping over quoted text and nested parentheses.
     *
     * @return the argument text, or empty when the argument list never closes
     */
    private static Optional<String> argumentOf(String text, int from) {
        int depth = 0;
        char quote = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    return Optional.of(text.substring(from, i));
                }
                depth--;
            }
        }
        return Optional.empty();
    }

    private static KeyResolver routeResolver(String endpointFile, String method, String protocol) {
        return (config, script, path) -> {
            Lookup lookup = serving(config, endpointFile, path, method);
            if (!(lookup instanceof Served(RouteHit route))) {
                return Optional.of(((Refused) lookup).reason());
            }
            Map<?, ?> gateway = config.yaml(GATEWAY);
            if (!(at(gateway, "anchors", route.anchor()) instanceof Map<?, ?>)) {
                return Optional.of("route " + route.id() + " rides anchor '" + route.anchor() + "', which "
                        + GATEWAY + " does not declare");
            }
            if (!protocol.isEmpty() && stringAt(route.route(), "protocol").filter(protocol::equals).isEmpty()) {
                return Optional.of("route " + route.id() + " does not declare protocol " + protocol);
            }
            ScopedBlock allowed = effectiveAllowedMethods(route, gateway);
            List<String> methods = listOf(allowed.block()).stream()
                    .map(String::valueOf)
                    .map(declared -> declared.toUpperCase(Locale.ROOT))
                    .toList();
            if (!methods.contains(method)) {
                return Optional.of("route " + route.id() + " does not admit " + method + ": the effective "
                        + "allowed_methods " + methods + " declared on " + allowed.scope() + " refuse it with 405");
            }
            return Optional.empty();
        };
    }

    private static KeyResolver upstreamRepointed(String endpointFile, String method, String composeService) {
        return (config, script, path) -> {
            Lookup lookup = serving(config, endpointFile, path, method);
            if (!(lookup instanceof Served(RouteHit route))) {
                return Optional.of(((Refused) lookup).reason() + ", so its upstream is not the one bound here");
            }
            Optional<String> alias = stringAt(route.endpoint(), "base_url");
            if (alias.isEmpty()) {
                return Optional.of("the endpoint serving " + path + " declares no base_url alias");
            }
            String value = config.properties(TOPOLOGY).getProperty(alias.get());
            if (value == null) {
                return Optional.of("alias " + alias.get() + " is not declared in " + TOPOLOGY);
            }
            Matcher placeholder = PLACEHOLDER.matcher(value.strip());
            if (!placeholder.matches()) {
                return Optional.of("alias " + alias.get() + " resolves to the fixed value '" + value
                        + "', so no overlay can repoint it at the benchmark backend");
            }
            String variable = placeholder.group(1);
            Map<?, ?> compose = config.yaml(COMPOSE_BENCHMARK);
            Optional<String> repointed =
                    environmentValue(at(compose, "services", composeService, "environment"), variable);
            if (repointed.isEmpty()) {
                return Optional.of(COMPOSE_BENCHMARK + " sets no " + variable + " on service " + composeService
                        + ", so the run would measure the integration-test upstream instead");
            }
            Optional<String> host = hostOf(repointed.get());
            if (host.isEmpty() || !(at(compose, "services", host.get()) instanceof Map<?, ?>)) {
                return Optional.of(variable + "=" + repointed.get() + " on " + composeService
                        + " names no service " + COMPOSE_BENCHMARK + " declares");
            }
            return Optional.empty();
        };
    }

    private static KeyResolver alpn() {
        return (config, script, protocol) -> {
            String id = ALPN_IDS.get(protocol);
            if (id == null) {
                return Optional.of("no ALPN identifier is known for the protocol " + protocol);
            }
            List<?> advertised = listAt(config.yaml(GATEWAY), "tls", "alpn");
            return advertised.contains(id) ? Optional.empty()
                    : Optional.of("tls.alpn " + advertised + " does not advertise " + id
                    + ", so the run would silently measure another protocol");
        };
    }

    private static KeyResolver passthroughSni() {
        return (config, script, host) -> {
            Object alias = at(config.yaml(GATEWAY), "tls", "passthrough_sni", host);
            if (!(alias instanceof String target)) {
                return Optional.of("tls.passthrough_sni maps no SNI host " + host);
            }
            return config.properties(TOPOLOGY).getProperty(target) != null ? Optional.empty()
                    : Optional.of("tls.passthrough_sni maps " + host + " to alias " + target + ", which "
                    + TOPOLOGY + " does not declare");
        };
    }

    private static KeyResolver issuer() {
        return (config, script, realm) -> {
            boolean declared = maps(at(config.yaml(GATEWAY), "token_validation", "issuers")).stream()
                    .map(entry -> stringAt(entry, "issuer"))
                    .flatMap(Optional::stream)
                    .anyMatch(issuer -> issuer.endsWith("/realms/" + realm));
            return declared ? Optional.empty()
                    : Optional.of("no token_validation issuer is the realm " + realm
                    + ", so every minted token would be rejected");
        };
    }

    private static KeyResolver oidcScopes() {
        return (config, script, requestedScopes) -> {
            List<String> required = listAt(config.yaml(GATEWAY), "oidc", "scopes").stream()
                    .map(String::valueOf)
                    .toList();
            if (required.isEmpty()) {
                return Optional.of("oidc.scopes is no longer declared, so the script's explicit scope request "
                        + "states a dependency the gateway no longer has");
            }
            Set<String> requested = Stream.of(requestedScopes.trim().split("\\s+")).collect(Collectors.toSet());
            List<String> missing = required.stream().filter(scope -> !requested.contains(scope)).toList();
            return missing.isEmpty() ? Optional.empty()
                    : Optional.of("oidc.scopes requires " + missing + ", which the token request does not ask for");
        };
    }

    private static KeyResolver bodyCapAbove(String endpointFile, String method) {
        return (config, script, bodyBytes) -> {
            Optional<String> path = firstGroup(TARGET_ROUTE_PATTERN, script);
            if (path.isEmpty()) {
                return Optional.of("the script names its route through no TARGET_URL declaration");
            }
            Lookup lookup = serving(config, endpointFile, path.get(), method);
            if (!(lookup instanceof Served(RouteHit route))) {
                return Optional.of(((Refused) lookup).reason());
            }
            Map<?, ?> gateway = config.yaml(GATEWAY);
            ScopedBlock effective = effectiveSecurityFilter(route, gateway);
            Object declared = at(effective.block(), "max_body_bytes");
            long cap;
            String source;
            if (declared instanceof Number number) {
                cap = number.longValue();
                source = "the max_body_bytes " + number + " of the security_filter declared on " + effective.scope();
            } else if (declared == null) {
                SecurityProfile global = SecurityProfile.parse(stringAt(gateway, "security_defaults", "profile")
                        .orElse(null)).orElse(SecurityProfile.DEFAULT_PROFILE);
                SecurityProfile profile = SecurityProfile.parse(stringAt(effective.block(), "profile").orElse(null))
                        .orElse(global);
                SecurityProfile limits = SecurityProfile.limitsProfile(profile, global);
                cap = limits.preset().maxBodySize();
                source = "the " + limits.name().toLowerCase(Locale.ROOT) + " preset's body cap " + cap
                        + ", in effect because the security_filter effective on " + effective.scope()
                        + " declares no max_body_bytes";
            } else {
                return Optional.of("max_body_bytes on " + effective.scope() + " is '" + declared + "', not a number");
            }
            return cap > Double.parseDouble(bodyBytes) ? Optional.empty()
                    : Optional.of(source + " does not exceed the " + bodyBytes
                    + "-byte body, so the run would measure the rejection path");
        };
    }

    /**
     * The {@code security_filter} block in effect on a route, by the gateway's rule
     * ({@link PolicyBlock#SECURITY_FILTER}): the nearest of route, endpoint and anchor that declares
     * a block wins and replaces every broader one wholesale — nothing of a broader block is merged
     * in. There is no gateway scope; {@code security_defaults} supplies only the profile.
     *
     * @return the effective block and where it is declared; a {@code null} block when no scope
     *         declares one
     */
    private static ScopedBlock effectiveSecurityFilter(RouteHit route, Map<?, ?> gateway) {
        List<ScopedBlock> nearestFirst = List.of(
                new ScopedBlock("route " + route.id(), at(route.route(), "security_filter")),
                new ScopedBlock("the endpoint of " + route.file(), at(route.endpoint(), "security_filter")),
                new ScopedBlock("anchor " + route.anchor(), at(gateway, "anchors", route.anchor(), "security_filter")));
        return nearestFirst.stream()
                .filter(candidate -> PolicyBlock.isDeclared(candidate.block()))
                .findFirst()
                .orElse(new ScopedBlock("no scope of route " + route.id(), null));
    }

    private static KeyResolver wsOrigin(String endpointFile, String method) {
        return (config, script, origin) -> {
            Optional<String> path = firstGroup(TARGET_ROUTE_PATTERN, script);
            if (path.isEmpty()) {
                return Optional.of("the script names its route through no TARGET_URL declaration");
            }
            Lookup lookup = serving(config, endpointFile, path.get(), method);
            if (!(lookup instanceof Served(RouteHit route))) {
                return Optional.of(((Refused) lookup).reason());
            }
            List<?> origins = listAt(route.route(), "websocket", "allowed_origins");
            return origins.contains(origin) ? Optional.empty()
                    : Optional.of("route " + route.id() + " allow-lists " + origins
                    + ", which does not admit the Origin the handshake sends");
        };
    }

    private static KeyResolver grpcMethod(String endpointFile) {
        KeyResolver route = routeResolver(endpointFile, POST, "grpc");
        return (config, script, serviceMethod) -> route.unresolved(config, script, "/" + serviceMethod);
    }

    private static KeyResolver protoServices(String endpointFile) {
        KeyResolver route = routeResolver(endpointFile, POST, "grpc");
        return (config, script, protoPackage) -> {
            List<String> services = allGroups(PROTO_SERVICE, script);
            if (services.isEmpty()) {
                return Optional.of("the descriptor declares no service");
            }
            List<String> failures = services.stream()
                    .map(service -> "/" + protoPackage + "." + service)
                    .flatMap(path -> route.unresolved(config, script, path).map(reason -> path + ": " + reason).stream())
                    .toList();
            return failures.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", failures));
        };
    }

    private static KeyResolver oidcLoginPath() {
        return (config, script, loginPath) -> {
            Optional<String> declared = stringAt(config.yaml(GATEWAY), "oidc", "login", "path");
            return declared.filter(loginPath::equals).isPresent() ? Optional.empty()
                    : Optional.of("oidc.login.path is " + declared.orElse("not declared"));
        };
    }

    /**
     * Finds the route the gateway serves a script's request on, and checks it is a route of the file
     * the binding expects. The route table is global (ADR-0007): a route of ANY enabled endpoint file
     * can take over a path, so the selection runs over all of them and a winner in another file is a
     * failure naming that file, not a quiet resolution through the expected one.
     *
     * @param config       the parsed configuration
     * @param expectedFile the endpoint file the binding expects to serve the path
     * @param path         the request path the script sends
     * @param method       the request method the script sends
     * @return the serving route, or why the expected file does not serve the path
     */
    private static Lookup serving(MountedConfig config, String expectedFile, String path, String method) {
        Optional<RouteHit> hit = routeFor(config, path, method);
        if (hit.isEmpty()) {
            return new Refused("no enabled route of any endpoint file has a match.path equal to " + path
                    + " or a match.path_prefix covering it for " + method);
        }
        RouteHit route = hit.get();
        if (route.selectsOnRequestDetail()) {
            return new Refused("route " + route.id() + " of " + route.file() + " is the first candidate for " + path
                    + " but also selects on match.host or match.headers, which this guard cannot evaluate for the "
                    + "script's request");
        }
        if (!route.file().equals(expectedFile)) {
            return new Refused("the gateway serves " + method + " " + path + " through route " + route.id() + " of "
                    + route.file() + ", not through a route of " + expectedFile);
        }
        return new Served(route);
    }

    /**
     * Selects the route a request is served by, the way the gateway does: over the routes of every
     * enabled endpoint document, in the route-table order ({@link #ROUTE_ORDER}), the first route
     * whose path matcher covers the path and whose {@code match.methods}, when declared, admit the
     * method (RouteTableBuilder orders the table, RouteSelectionStage selects from it).
     */
    private static Optional<RouteHit> routeFor(MountedConfig config, String path, String method) {
        return endpointRoutes(config).stream()
                .sorted(ROUTE_ORDER)
                .filter(route -> route.covers(path) && route.admitsByMatch(method))
                .findFirst();
    }

    /** Every route of every enabled endpoint document of the configuration. */
    private static List<RouteHit> endpointRoutes(MountedConfig config) {
        List<RouteHit> routes = new ArrayList<>();
        config.documents().forEach((file, document) -> {
            if (file.startsWith(ENDPOINTS) && at(document, "endpoint") instanceof Map<?, ?> endpoint
                    && !Boolean.FALSE.equals(endpoint.get("enabled"))) {
                maps(endpoint.get("routes")).forEach(route -> routes.add(new RouteHit(file, endpoint, route)));
            }
        });
        return routes;
    }

    /**
     * The {@code allowed_methods} in effect on a route, by the gateway's rule
     * ({@link PolicyBlock#ALLOWED_METHODS}, enforced by the verb gate after route selection): the
     * nearest of endpoint, anchor and gateway that declares a non-empty list wins and replaces every
     * broader one wholesale; when none declares one, every standard method is allowed.
     * <p>
     * A route's own {@code match.methods} is not part of this list. The gateway consults it FIRST,
     * at route selection ({@link #routeFor}), and a request must pass both: a route whose
     * {@code match.methods} refuse the method is never selected, and a selected route still
     * answers 405 when this list refuses it.
     *
     * @return the effective list and where it is declared
     */
    private static ScopedBlock effectiveAllowedMethods(RouteHit route, Map<?, ?> gateway) {
        List<ScopedBlock> nearestFirst = List.of(
                new ScopedBlock("the endpoint of " + route.file(), at(route.endpoint(), "allowed_methods")),
                new ScopedBlock("anchor " + route.anchor(), at(gateway, "anchors", route.anchor(), "allowed_methods")),
                new ScopedBlock(GATEWAY, at(gateway, "allowed_methods")));
        return nearestFirst.stream()
                .filter(candidate -> PolicyBlock.isDeclared(candidate.block()))
                .findFirst()
                .orElse(new ScopedBlock("no scope, so every standard method",
                        Stream.of(HttpMethod.values()).map(HttpMethod::name).toList()));
    }

    private static Optional<String> environmentValue(Object environment, String variable) {
        if (environment instanceof Map<?, ?> map) {
            return Optional.ofNullable(map.get(variable)).map(String::valueOf);
        }
        String assignment = variable + "=";
        return listOf(environment).stream()
                .map(String::valueOf)
                .filter(entry -> entry.startsWith(assignment))
                .map(entry -> entry.substring(assignment.length()))
                .findFirst();
    }

    private static Optional<String> hostOf(String url) {
        try {
            return Optional.ofNullable(URI.create(url).getHost());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Object at(Object root, String... keys) {
        Object current = root;
        for (String key : keys) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }

    private static Optional<String> stringAt(Object root, String... keys) {
        return at(root, keys) instanceof String value ? Optional.of(value) : Optional.empty();
    }

    private static List<?> listAt(Object root, String... keys) {
        return listOf(at(root, keys));
    }

    private static List<?> listOf(Object node) {
        return node instanceof List<?> list ? list : List.of();
    }

    private static List<Map<?, ?>> maps(Object node) {
        return listOf(node).stream()
                .filter(Map.class::isInstance)
                .<Map<?, ?>>map(entry -> (Map<?, ?>) entry)
                .toList();
    }

    private static Optional<String> firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static List<String> allGroups(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        List<String> groups = new ArrayList<>();
        while (matcher.find()) {
            groups.add(matcher.group(1));
        }
        return groups;
    }

    /**
     * @return every regular file under the k6 directory, as a {@code /}-separated path relative to it
     */
    private static SortedSet<String> walkedPopulation() throws IOException {
        assertTrue(Files.isDirectory(K6_SCRIPTS),
                () -> "the k6 script directory is not where this guard looks for it: " + K6_SCRIPTS);
        try (Stream<Path> paths = Files.walk(K6_SCRIPTS)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> K6_SCRIPTS.relativize(path).toString().replace('\\', '/'))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /**
     * @return the JavaScript files of the walked population — the only files that can call a target
     *         resolver, since the resolvers are JavaScript functions
     */
    private static SortedSet<String> javaScriptPopulation() throws IOException {
        return walkedPopulation().stream()
                .filter(script -> script.endsWith(".js"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String scriptText(String script) throws IOException {
        Path path = K6_SCRIPTS.resolve(script);
        assertTrue(Files.isRegularFile(path), () -> "the k6 directory has no file " + script + ": " + path);
        return Files.readString(path);
    }

    /**
     * @return the committed text of every configuration file the table reads, keyed by its path
     *         relative to the module root
     */
    private static Map<String, String> diskSources() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String file : CONFIG_FILES) {
            Path path = MODULE.resolve(file);
            assertTrue(Files.isRegularFile(path),
                    () -> "a configuration file this table reads is not where it looks for it: " + path);
            sources.put(file, Files.readString(path));
        }
        return sources;
    }

    /**
     * @param named the files the rows name, relative to the module root
     * @return the named files plus every YAML document under the mounted endpoint directory, sorted
     */
    private static List<String> mountedConfigFiles(List<String> named) {
        SortedSet<String> files = new TreeSet<>(named);
        try (Stream<Path> endpoints = Files.list(MODULE.resolve(ENDPOINTS))) {
            endpoints.filter(Files::isRegularFile)
                    .map(path -> ENDPOINTS + path.getFileName())
                    .filter(file -> file.endsWith(".yaml") || file.endsWith(".yml"))
                    .forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list the mounted endpoint documents under " + ENDPOINTS, e);
        }
        return List.copyOf(files);
    }

    private static MountedConfig specimenConfig(String file, String specimen) throws IOException {
        Map<String, String> sources = diskSources();
        sources.put(file, specimen);
        return MountedConfig.parse(sources);
    }

    private static Stream<Binding> bindings() {
        return TABLE.stream().filter(Binding.class::isInstance).map(Binding.class::cast);
    }

    private static Binding binding(String script, String key) {
        List<Binding> matching = bindings()
                .filter(row -> row.script().equals(script) && row.key().equals(key))
                .toList();
        assertEquals(1, matching.size(), () -> "expected exactly one " + key + " row for " + script);
        return matching.getFirst();
    }

    private static Binding bind(String script, String key, String extraction, String configFile,
            String resolution, KeyResolver resolver) {
        return new Binding(script, key, Pattern.compile(extraction), configFile, resolution, resolver);
    }

    /** One file of the k6 directory. */
    private sealed interface Row permits Binding, ZeroKey {

        /** @return the file, relative to the k6 directory */
        String script();
    }

    /**
     * Binds one literal of a script to the configuration key it depends on.
     *
     * @param script     the file, relative to the k6 directory
     * @param key        what the script depends on, as named in failure messages
     * @param extraction the pattern whose first group is the literal, read from the script text
     * @param configFile the configuration file the key lives in, relative to the module root
     * @param resolution how the literal is resolved against that file
     * @param resolver   the resolution itself
     */
    private record Binding(String script, String key, Pattern extraction, String configFile, String resolution,
    KeyResolver resolver) implements Row {

        @Override
        public String toString() {
            return script + " -> " + key + " in " + configFile;
        }
    }

    /**
     * States why a file of the k6 directory depends on no key of the mounted configuration.
     *
     * @param script the file, relative to the k6 directory
     * @param reason why it depends on no mounted key
     */
    private record ZeroKey(String script, String reason) implements Row {
    }

    /**
     * What the discovery leg read from one script's target resolver calls.
     *
     * @param literals the route literals handed to a resolver, in script order
     * @param refusals one message per resolver call whose argument is not a route literal, each
     *                 naming the script and the call
     */
    private record Discovery(List<String> literals, List<String> refusals) {
    }

    /** Resolves one extracted literal against the parsed configuration. */
    @FunctionalInterface
    private interface KeyResolver {

        /**
         * @param config  the parsed configuration
         * @param script  the full text of the script the literal was extracted from
         * @param literal the extracted literal
         * @return why the literal does not resolve, or empty when it does
         */
        Optional<String> unresolved(MountedConfig config, String script, String literal);
    }

    /**
     * A policy block as declared at one scope.
     *
     * @param scope where the block is declared, as named in failure messages
     * @param block the declared block, {@code null} when the scope declares none
     */
    private record ScopedBlock(String scope, Object block) {
    }

    /** The outcome of {@link #serving}: the serving route, or why the expected file does not serve. */
    private sealed interface Lookup permits Served, Refused {
    }

    /** @param route the route the expected file serves the path through */
    private record Served(RouteHit route) implements Lookup {
    }

    /** @param reason why the expected file does not serve the path */
    private record Refused(String reason) implements Lookup {
    }

    /**
     * One route of the configuration, together with the endpoint and the file that declare it.
     *
     * @param file     the endpoint document, relative to the module root
     * @param endpoint the endpoint block of the file
     * @param route    the route
     */
    private record RouteHit(String file, Map<?, ?> endpoint, Map<?, ?> route) {

        String id() {
            return stringAt(route, "id").orElse("<unnamed>");
        }

        /** @return the route's own anchor, else the endpoint's */
        String anchor() {
            return stringAt(route, "anchor").or(() -> stringAt(endpoint, "anchor")).orElse("");
        }

        /** @return whether the route declares an exact {@code match.path} */
        boolean exact() {
            return stringAt(route, "match", "path").isPresent();
        }

        /** @return the exact path, else the path prefix, as declared */
        String matchKey() {
            return stringAt(route, "match", "path").or(() -> stringAt(route, "match", "path_prefix")).orElse("");
        }

        /** @return the exact path verbatim, or the prefix normalized the way the route table orders it */
        String orderingKey() {
            return exact() ? matchKey() : RouteTableBuilder.normalizePrefix(matchKey());
        }

        /**
         * The gateway's path test (RouteMatcher): equality with the match key, or for a prefix a
         * path at or below it on a segment boundary.
         */
        boolean covers(String path) {
            String key = matchKey();
            if (key.isEmpty()) {
                return false;
            }
            if (path.equals(key)) {
                return true;
            }
            return !exact() && path.startsWith(key.endsWith("/") ? key : key + "/");
        }

        /** @return whether the route's {@code match.methods}, when declared, contain the method */
        boolean admitsByMatch(String method) {
            List<?> methods = listAt(route, "match", "methods");
            return methods.isEmpty() || methods.stream().map(String::valueOf).anyMatch(method::equalsIgnoreCase);
        }

        /** @return whether the route also selects on the request's host or headers */
        boolean selectsOnRequestDetail() {
            return at(route, "match", "host") != null || !listAt(route, "match", "headers").isEmpty();
        }
    }

    /**
     * The parsed mounted configuration: each YAML file as its document mapping, each properties file
     * as its properties, keyed by the path relative to the module root.
     *
     * @param documents the parsed documents
     */
    private record MountedConfig(Map<String, Object> documents) {

        static MountedConfig parse(Map<String, String> sources) {
            Map<String, Object> documents = new LinkedHashMap<>();
            sources.forEach((file, source) -> documents.put(file, parseDocument(file, source)));
            return new MountedConfig(Map.copyOf(documents));
        }

        private static Object parseDocument(String file, String source) {
            if (file.endsWith(".properties")) {
                Properties properties = new Properties();
                try {
                    properties.load(new StringReader(source));
                } catch (IOException e) {
                    throw new UncheckedIOException("cannot read " + file, e);
                }
                assertFalse(properties.isEmpty(), () -> "the properties file parsed to no entry: " + file);
                return properties;
            }
            Object document = new Yaml().load(source);
            return assertInstanceOf(Map.class, document, () -> "the YAML file must parse into a mapping: " + file);
        }

        Map<?, ?> yaml(String file) {
            return assertInstanceOf(Map.class, documents.get(file), () -> "no YAML document loaded for " + file);
        }

        Properties properties(String file) {
            return assertInstanceOf(Properties.class, documents.get(file),
                    () -> "no properties document loaded for " + file);
        }
    }
}
