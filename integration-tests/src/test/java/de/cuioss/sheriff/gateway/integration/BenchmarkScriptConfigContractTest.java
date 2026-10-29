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
 * <strong>Discovery leg.</strong> Every route literal a script passes to {@code targetUrl},
 * {@code wsUrl} or {@code passthroughEmptyUrl} is discovered from the script text and must be covered
 * by a binding of that script, so a new script dependency cannot be added without a row.
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
    private static final String GATEWAY = CONFIG_DIR + "gateway.yaml";
    private static final String TOPOLOGY = CONFIG_DIR + "topology.properties";
    private static final String HTTPBIN = CONFIG_DIR + "endpoints/httpbin.yaml";
    private static final String SECURE = CONFIG_DIR + "endpoints/secure.yaml";
    private static final String WEBSOCKET = CONFIG_DIR + "endpoints/websocket.yaml";
    private static final String GRPC = CONFIG_DIR + "endpoints/grpc.yaml";
    private static final String BFF_SESSION = CONFIG_DIR + "endpoints/bff-session.yaml";
    private static final String COMPOSE_BENCHMARK = "docker-compose.benchmark.yml";

    /** Every mounted configuration file a resolver below reads, relative to the module root. */
    private static final List<String> CONFIG_FILES = List.of(
            GATEWAY, TOPOLOGY, HTTPBIN, SECURE, WEBSOCKET, GRPC, BFF_SESSION, COMPOSE_BENCHMARK);

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

    /** Every route literal handed to one of the three target resolvers of {@code lib/target.js}. */
    private static final Pattern RESOLVER_CALL =
            Pattern.compile("\\b(?:targetUrl|wsUrl|passthroughEmptyUrl)\\(\\s*'([^']*)'\\s*\\)");

    private static final Pattern PROTO_SERVICE = Pattern.compile("(?m)^service\\s+(\\w+)\\s*\\{");

    /** The ADR-0004 Amendment A1 in-file placeholder form an overlay can repoint. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(\\w+)(?::-[^}]*)?}");

    /** The ALPN protocol identifier behind each protocol name k6 reports on a response. */
    private static final Map<String, String> ALPN_IDS = Map.of("HTTP/2.0", "h2", "HTTP/1.1", "http/1.1");

    private static final String ROUTE_RESOLUTION =
            "exact match.path or longest covering match.path_prefix over the parsed endpoint routes, "
                    + "with the route admitting the method the script sends";
    private static final String UPSTREAM_RESOLUTION =
            "the serving endpoint's base_url alias is a ${VAR:-default} placeholder in topology.properties, "
                    + "and " + COMPOSE_BENCHMARK + " sets VAR on the instance to a service it declares";

    /** The key-to-script table: every file of the k6 directory, with its keys or a zero-key reason. */
    private static final List<Row> TABLE = List.of(
            bind("proxied_static.js", ROUTE, TARGET_ROUTE, HTTPBIN, ROUTE_RESOLUTION,
                    routeResolver(HTTPBIN, GET, ANY_PROTOCOL)),
            bind("proxied_static.js", UPSTREAM, TARGET_ROUTE, TOPOLOGY, UPSTREAM_RESOLUTION,
                    upstreamRepointed(HTTPBIN, "api-sheriff")),

            bind("http2.js", ROUTE, TARGET_ROUTE, HTTPBIN, ROUTE_RESOLUTION,
                    routeResolver(HTTPBIN, GET, ANY_PROTOCOL)),
            bind("http2.js", ALPN, "const EXPECTED_PROTO = '([^']+)'", GATEWAY,
                    "YAML key path tls.alpn containing the ALPN id of the negotiated protocol", alpn()),
            bind("http2.js", UPSTREAM, TARGET_ROUTE, TOPOLOGY, UPSTREAM_RESOLUTION,
                    upstreamRepointed(HTTPBIN, "api-sheriff")),

            bind("passthrough_relay.js", SNI,
                    "const PASSTHROUGH_TARGET_URL = __ENV\\.PASSTHROUGH_TARGET_URL \\|\\| 'https://([^:/']+)",
                    GATEWAY, "YAML key path tls.passthrough_sni.<host>, its alias declared in topology.properties",
                    passthroughSni()),
            bind("passthrough_relay.js", ROUTE, "passthroughEmptyUrl\\('([^']+)'\\)", HTTPBIN, ROUTE_RESOLUTION,
                    routeResolver(HTTPBIN, GET, ANY_PROTOCOL)),
            bind("passthrough_relay.js", UPSTREAM, "passthroughEmptyUrl\\('([^']+)'\\)", TOPOLOGY,
                    UPSTREAM_RESOLUTION, upstreamRepointed(HTTPBIN, "api-sheriff-passthrough-empty")),

            bind("bearer_proxied.js", ROUTE, TARGET_ROUTE, SECURE, ROUTE_RESOLUTION,
                    routeResolver(SECURE, GET, ANY_PROTOCOL)),
            bind("bearer_proxied.js", ISSUER,
                    "const KEYCLOAK_TOKEN_URL = __ENV\\.KEYCLOAK_TOKEN_URL\\s*\\|\\|\\s*'[^']*/realms/([^/']+)/",
                    GATEWAY, "YAML key path token_validation.issuers[].issuer ending in /realms/<realm>", issuer()),
            bind("bearer_proxied.js", SCOPES, "\\bscope: '([^']+)'", GATEWAY,
                    "YAML key path oidc.scopes, every member requested by the script", oidcScopes()),
            bind("bearer_proxied.js", UPSTREAM, TARGET_ROUTE, TOPOLOGY, UPSTREAM_RESOLUTION,
                    upstreamRepointed(SECURE, "api-sheriff")),

            bind("graphql.js", ROUTE, TARGET_ROUTE, HTTPBIN, ROUTE_RESOLUTION,
                    routeResolver(HTTPBIN, POST, ANY_PROTOCOL)),
            bind("graphql.js", UPSTREAM, TARGET_ROUTE, TOPOLOGY, UPSTREAM_RESOLUTION,
                    upstreamRepointed(HTTPBIN, "api-sheriff")),

            bind("upload_small.js", ROUTE, TARGET_ROUTE, HTTPBIN, ROUTE_RESOLUTION,
                    routeResolver(HTTPBIN, POST, ANY_PROTOCOL)),
            bind("upload_small.js", BODY_CAP, "const BODY_BYTES = ([0-9.eE+]+);", GATEWAY,
                    "max_body_bytes declared on the serving route or its anchor, above the body size",
                    bodyCapAbove(HTTPBIN)),
            bind("upload_small.js", UPSTREAM, TARGET_ROUTE, TOPOLOGY, UPSTREAM_RESOLUTION,
                    upstreamRepointed(HTTPBIN, "api-sheriff")),

            bind("upload_large.js", ROUTE, TARGET_ROUTE, HTTPBIN, ROUTE_RESOLUTION,
                    routeResolver(HTTPBIN, POST, ANY_PROTOCOL)),
            bind("upload_large.js", BODY_CAP, "const BODY_BYTES = ([0-9.eE+]+);", GATEWAY,
                    "max_body_bytes declared on the serving route or its anchor, above the body size",
                    bodyCapAbove(HTTPBIN)),
            bind("upload_large.js", UPSTREAM, TARGET_ROUTE, TOPOLOGY, UPSTREAM_RESOLUTION,
                    upstreamRepointed(HTTPBIN, "api-sheriff")),

            bind("websocket_echo.js", ROUTE, TARGET_ROUTE, WEBSOCKET, ROUTE_RESOLUTION + ", protocol websocket",
                    routeResolver(WEBSOCKET, GET, "websocket")),
            bind("websocket_echo.js", ORIGIN, "const ORIGIN = __ENV\\.WS_ORIGIN \\|\\| '([^']+)'", WEBSOCKET,
                    "websocket.allowed_origins of the route serving the script's TARGET_URL", wsOrigin(WEBSOCKET)),

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
                    upstreamRepointed(BFF_SESSION, "api-sheriff")),

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
    void everyWalkedFileHasARowAndNoRowIsStale() throws IOException {
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
    Stream<DynamicTest> everyBindingResolvesAgainstTheMountedConfiguration() throws IOException {
        MountedConfig config = MountedConfig.parse(diskSources());

        return bindings().map(binding -> DynamicTest.dynamicTest(binding.toString(), () -> {
            Optional<String> failure = failureOf(binding, scriptText(binding.script()), config);

            assertFalse(failure.isPresent(), () -> failure.orElse(""));
        }));
    }

    @Test
    @DisplayName("every route literal a script hands to a target resolver is discovered and covered by a binding")
    void everyDiscoveredRouteLiteralIsCoveredByABinding() throws IOException {
        Map<String, List<String>> discovered = new LinkedHashMap<>();
        for (String script : walkedPopulation()) {
            List<String> literals = allGroups(RESOLVER_CALL, scriptText(script));
            if (!literals.isEmpty()) {
                discovered.put(script, literals);
            }
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
                () -> assertTrue(uncovered.isEmpty(),
                        () -> "these route literals are handed to a target resolver but no binding of their "
                                + "script extracts them, so their configuration key is unguarded: " + uncovered));
    }

    @Test
    @DisplayName("every configuration file the table reads exists and parses, and every row names one of them")
    void everyConfigurationFileExistsAndParses() throws IOException {
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
        void aRenamedRoutePrefixIsRejected() throws IOException {
            MountedConfig specimen = specimenConfig(HTTPBIN, RENAMED_UPLOAD_PREFIX);

            Optional<String> failure = failureOf(binding("upload_small.js", ROUTE), scriptText("upload_small.js"),
                    specimen);

            assertTrue(failure.isPresent(), "a renamed /upload/small prefix must be rejected");
            assertTrue(failure.orElseThrow().startsWith("upload_small.js "),
                    () -> "the rejection must name the script that must follow: " + failure.orElseThrow());
        }

        @Test
        @DisplayName("a renamed passthrough SNI host is rejected, naming the script that must follow")
        void aRenamedSniHostIsRejected() throws IOException {
            MountedConfig specimen = specimenConfig(GATEWAY, RENAMED_SNI_HOST);

            Optional<String> failure = failureOf(binding("passthrough_relay.js", SNI),
                    scriptText("passthrough_relay.js"), specimen);

            assertTrue(failure.isPresent(), "a renamed passthrough_sni host must be rejected");
            assertTrue(failure.orElseThrow().startsWith("passthrough_relay.js "),
                    () -> "the rejection must name the script that must follow: " + failure.orElseThrow());
        }

        @Test
        @DisplayName("a dropped WebSocket Origin is rejected while the route it rides still resolves")
        void aDroppedOriginIsRejected() throws IOException {
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
        void aReshapedDocumentThatKeepsTheKeyIsAccepted() throws IOException {
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

    private static KeyResolver routeResolver(String endpointFile, String method, String protocol) {
        return (config, script, path) -> {
            Optional<RouteHit> hit = routeFor(config.yaml(endpointFile), path);
            if (hit.isEmpty()) {
                return Optional.of("no enabled route has a match.path equal to " + path
                        + " or a match.path_prefix covering it");
            }
            RouteHit route = hit.get();
            Map<?, ?> gateway = config.yaml(GATEWAY);
            if (!(at(gateway, "anchors", route.anchor()) instanceof Map<?, ?>)) {
                return Optional.of("route " + route.id() + " rides anchor '" + route.anchor() + "', which "
                        + GATEWAY + " does not declare");
            }
            if (!protocol.isEmpty() && stringAt(route.route(), "protocol").filter(protocol::equals).isEmpty()) {
                return Optional.of("route " + route.id() + " does not declare protocol " + protocol);
            }
            List<String> methods = effectiveMethods(route, gateway);
            if (!methods.contains(method)) {
                return Optional.of("route " + route.id() + " does not admit " + method + " (effective methods "
                        + methods + ")");
            }
            return Optional.empty();
        };
    }

    private static KeyResolver upstreamRepointed(String endpointFile, String composeService) {
        return (config, script, path) -> {
            Optional<RouteHit> hit = routeFor(config.yaml(endpointFile), path);
            if (hit.isEmpty()) {
                return Optional.of("no enabled route serves " + path + ", so its upstream cannot be followed");
            }
            Optional<String> alias = stringAt(hit.get().endpoint(), "base_url");
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

    private static KeyResolver bodyCapAbove(String endpointFile) {
        return (config, script, bodyBytes) -> {
            Optional<String> path = firstGroup(TARGET_ROUTE_PATTERN, script);
            if (path.isEmpty()) {
                return Optional.of("the script names its route through no TARGET_URL declaration");
            }
            Optional<RouteHit> hit = routeFor(config.yaml(endpointFile), path.get());
            if (hit.isEmpty()) {
                return Optional.of("no enabled route serves " + path.get());
            }
            Object cap = at(hit.get().route(), "security_filter", "max_body_bytes");
            if (cap == null) {
                cap = at(config.yaml(GATEWAY), "anchors", hit.get().anchor(), "security_filter", "max_body_bytes");
            }
            if (!(cap instanceof Number declared)) {
                return Optional.of("neither route " + hit.get().id() + " nor anchor " + hit.get().anchor()
                        + " declares max_body_bytes, so the body is measured against the profile cap");
            }
            return declared.doubleValue() > Double.parseDouble(bodyBytes) ? Optional.empty()
                    : Optional.of("the declared max_body_bytes " + declared + " does not exceed the " + bodyBytes
                            + "-byte body, so the run would measure the rejection path");
        };
    }

    private static KeyResolver wsOrigin(String endpointFile) {
        return (config, script, origin) -> {
            Optional<String> path = firstGroup(TARGET_ROUTE_PATTERN, script);
            if (path.isEmpty()) {
                return Optional.of("the script names its route through no TARGET_URL declaration");
            }
            Optional<RouteHit> hit = routeFor(config.yaml(endpointFile), path.get());
            if (hit.isEmpty()) {
                return Optional.of("no enabled route serves " + path.get());
            }
            List<?> origins = listAt(hit.get().route(), "websocket", "allowed_origins");
            return origins.contains(origin) ? Optional.empty()
                    : Optional.of("route " + hit.get().id() + " allow-lists " + origins
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
     * Selects the route a path is served by, the way the endpoint file states it: an exact
     * {@code match.path} first, otherwise the longest {@code match.path_prefix} that covers the path
     * on a segment boundary.
     */
    private static Optional<RouteHit> routeFor(Map<?, ?> endpointDocument, String path) {
        if (!(endpointDocument.get("endpoint") instanceof Map<?, ?> endpoint)
                || Boolean.FALSE.equals(endpoint.get("enabled"))) {
            return Optional.empty();
        }
        List<Map<?, ?>> routes = maps(endpoint.get("routes"));
        Optional<Map<?, ?>> exact = routes.stream()
                .filter(route -> path.equals(at(route, "match", "path")))
                .findFirst();
        if (exact.isPresent()) {
            return Optional.of(new RouteHit(endpoint, exact.get()));
        }
        return routes.stream()
                .filter(route -> stringAt(route, "match", "path_prefix").filter(prefix -> covers(prefix, path)).isPresent())
                .max(Comparator.comparingInt(route -> stringAt(route, "match", "path_prefix").orElse("").length()))
                .map(route -> new RouteHit(endpoint, route));
    }

    private static boolean covers(String prefix, String path) {
        String bare = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return path.equals(bare) || path.startsWith(bare + "/");
    }

    /** The methods a route admits: its own match.methods, else its anchor's, else the global list. */
    private static List<String> effectiveMethods(RouteHit route, Map<?, ?> gateway) {
        List<?> declared = listAt(route.route(), "match", "methods");
        if (declared.isEmpty()) {
            declared = listAt(gateway, "anchors", route.anchor(), "allowed_methods");
        }
        if (declared.isEmpty()) {
            declared = listAt(gateway, "allowed_methods");
        }
        return declared.stream().map(String::valueOf).map(method -> method.toUpperCase(Locale.ROOT)).toList();
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
     * The route a path selects, together with the endpoint that declares it.
     *
     * @param endpoint the endpoint block of the file
     * @param route    the selected route
     */
    private record RouteHit(Map<?, ?> endpoint, Map<?, ?> route) {

        String id() {
            return stringAt(route, "id").orElse("<unnamed>");
        }

        /** @return the route's own anchor, else the endpoint's */
        String anchor() {
            return stringAt(route, "anchor").or(() -> stringAt(endpoint, "anchor")).orElse("");
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
