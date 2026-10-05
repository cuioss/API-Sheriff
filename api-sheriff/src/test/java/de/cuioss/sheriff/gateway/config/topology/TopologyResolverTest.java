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
package de.cuioss.sheriff.gateway.config.topology;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;


import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.load.DefaultedPlaceholder;
import de.cuioss.sheriff.gateway.config.load.EnvSecretResolver;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.topology.TopologyResolver.TopologyResolutionException;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link TopologyResolver}: file resolution, URL decomposition with
 * scheme-default ports, the D4 {@code ${VAR}} / {@code ${VAR:-default}} substitution
 * over {@code topology.properties} values (there is no convention-named
 * {@code TOPOLOGY_<ALIAS>} environment precedence path), the boot failures for
 * malformed or missing aliases referenced by enabled endpoints — every failure of one
 * pass reported together as {@link ConfigError}s — the report/skip asymmetry between
 * enabled-endpoint {@code base_url} aliases and additional aliases, and the name-only
 * reporting of applied in-file defaults.
 */
@EnableGeneratorController
class TopologyResolverTest {

    @TempDir
    Path directory;

    private static EndpointConfig endpointFor(String alias) {
        return EndpointConfig.builder()
                .id(alias.toLowerCase(Locale.ROOT))
                .enabled(true)
                .baseUrl(alias)
                .auth(new AuthConfig(Require.NONE, null, null))
                .build();
    }

    private Path topologyFile(String content) throws IOException {
        Path file = directory.resolve("topology.properties");
        Files.writeString(file, content);
        return file;
    }

    private static TopologyResolver resolverWith(Map<String, String> environment) {
        return new TopologyResolver(new EnvSecretResolver(environment::get));
    }

    private static ConfigError topologyError(String alias, String message) {
        return new ConfigError("topology.properties", alias, message);
    }

    @Test
    void resolvesFromFileAndDecomposesComponents() throws Exception {
        Path file = topologyFile("ORDERS=https://orders.internal:8443/api\n");

        ResolvedTopology topology = resolverWith(Map.of())
                .resolve(file, List.of(endpointFor("ORDERS")), List.of());

        ResolvedUpstream upstream = topology.lookup("ORDERS").orElseThrow();
        assertEquals("https", upstream.scheme());
        assertEquals("orders.internal", upstream.host());
        assertEquals(8443, upstream.port());
        assertEquals("/api", upstream.basePath());
    }

    @Test
    void defaultsPortFromSchemeWhenAbsent() throws Exception {
        Path file = topologyFile("USERS=https://users.internal\nORDERS=http://orders.internal\n");

        ResolvedTopology topology = resolverWith(Map.of())
                .resolve(file, List.of(endpointFor("USERS"), endpointFor("ORDERS")), List.of());

        assertEquals(443, topology.lookup("USERS").orElseThrow().port());
        assertEquals(80, topology.lookup("ORDERS").orElseThrow().port());
        assertEquals("", topology.lookup("USERS").orElseThrow().basePath());
    }

    @Test
    @DisplayName("An in-file ${VAR} placeholder resolves the topology value from the environment")
    void resolvesInFilePlaceholderFromEnvironment() throws Exception {
        Path file = topologyFile("ORDERS=${ORDERS_URL}\n");

        ResolvedTopology topology = resolverWith(Map.of("ORDERS_URL", "https://env.host:2000/env"))
                .resolve(file, List.of(endpointFor("ORDERS")), List.of());

        ResolvedUpstream upstream = topology.lookup("ORDERS").orElseThrow();
        assertEquals("env.host", upstream.host());
        assertEquals(2000, upstream.port());
        assertEquals("/env", upstream.basePath());
    }

    @Test
    @DisplayName("An in-file ${VAR:-default} placeholder applies its literal default when the variable is unset")
    void appliesDefaultForUnsetInFilePlaceholder() throws Exception {
        Path file = topologyFile("ORDERS=${ORDERS_URL:-https://default.host:3000/def}\n");

        ResolvedTopology topology = resolverWith(Map.of())
                .resolve(file, List.of(endpointFor("ORDERS")), List.of());

        ResolvedUpstream upstream = topology.lookup("ORDERS").orElseThrow();
        assertEquals("default.host", upstream.host());
        assertEquals(3000, upstream.port());
        assertEquals("/def", upstream.basePath());
    }

    @Test
    @DisplayName("A ${VAR} placeholder on an enabled endpoint alias fails the boot when the variable is unset")
    void throwsWhenEnabledEndpointPlaceholderNamesUnsetVariable() throws Exception {
        Path file = topologyFile("ORDERS=${ORDERS_URL}\n");
        TopologyResolver resolver = resolverWith(Map.of());
        List<EndpointConfig> endpoints = List.of(endpointFor("ORDERS"));

        TopologyResolutionException exception = assertThrows(TopologyResolutionException.class,
                () -> resolver.resolve(file, endpoints, List.of()),
                "an unresolved ${VAR} on an enabled endpoint alias must fail the boot");

        assertEquals(List.of(topologyError("ORDERS",
                "Cannot resolve placeholder in topology alias 'ORDERS': Unresolved environment variable: ORDERS_URL")),
                exception.errors());
    }

    @Test
    void trimsSurroundingWhitespaceBeforeParsing() throws Exception {
        Path file = topologyFile("ORDERS=  https://orders.internal:8443/api  \n");

        ResolvedTopology topology = resolverWith(Map.of())
                .resolve(file, List.of(endpointFor("ORDERS")), List.of());

        ResolvedUpstream upstream = topology.lookup("ORDERS").orElseThrow();
        assertEquals("orders.internal", upstream.host());
        assertEquals(8443, upstream.port());
        assertEquals("/api", upstream.basePath());
    }

    @ParameterizedTest(name = "rejects {1}")
    @MethodSource("invalidTopologies")
    void rejectsInvalidTopology(String topologyContent, String scenario, String endpointAlias, String expectedMessage)
            throws Exception {
        Path file = topologyFile(topologyContent);
        TopologyResolver resolver = resolverWith(Map.of());
        List<EndpointConfig> endpoints = List.of(endpointFor(endpointAlias));

        TopologyResolutionException exception = assertThrows(TopologyResolutionException.class,
                () -> resolver.resolve(file, endpoints, List.of()));

        assertEquals(List.of(topologyError(endpointAlias, expectedMessage)), exception.errors(), scenario);
    }

    static Stream<Arguments> invalidTopologies() {
        return Stream.of(
                Arguments.of("ORDERS=http://orders internal:8443\n", "malformed url", "ORDERS",
                        "Malformed topology URL for alias 'ORDERS'"),
                Arguments.of("ORDERS=orders.internal:8443\n", "url without scheme or host", "ORDERS",
                        "Topology URL for alias 'ORDERS' must be absolute with scheme and host"),
                Arguments.of("OTHER=https://other.internal\n", "missing alias referenced by enabled endpoint",
                        "MISSING", "Unresolved topology alias 'MISSING' referenced by enabled endpoint 'missing'"));
    }

    @Test
    @DisplayName("Every topology failure of one pass is reported together, in resolution order")
    void reportsEveryFailureOfOnePassInOneException() throws Exception {
        Path file = topologyFile("ALPHA=${ALPHA_URL}\nBETA=${BETA_HOST}-${BETA_PORT}\n");
        TopologyResolver resolver = resolverWith(Map.of());
        List<EndpointConfig> endpoints = List.of(endpointFor("ALPHA"), endpointFor("BETA"), endpointFor("GAMMA"));

        TopologyResolutionException exception = assertThrows(TopologyResolutionException.class,
                () -> resolver.resolve(file, endpoints, List.of()));

        assertEquals(List.of(
                topologyError("ALPHA",
                        "Cannot resolve placeholder in topology alias 'ALPHA': Unresolved environment variable: ALPHA_URL"),
                topologyError("BETA", "Cannot resolve placeholder in topology alias 'BETA': "
                        + "Unresolved environment variable: BETA_HOST, BETA_PORT"),
                topologyError("GAMMA", "Unresolved topology alias 'GAMMA' referenced by enabled endpoint 'gamma'")),
                exception.errors());
        assertTrue(exception.getMessage().contains("3 error(s)"),
                () -> "the exception message summarises every failure: " + exception.getMessage());
    }

    @Test
    @DisplayName("An alias shared by two enabled endpoints is refused once, not once per endpoint")
    void reportsSharedFailingAliasOnce() throws Exception {
        Path file = topologyFile("OTHER=https://other.internal\n");
        TopologyResolver resolver = resolverWith(Map.of());
        EndpointConfig second = EndpointConfig.builder()
                .id("second")
                .enabled(true)
                .baseUrl("MISSING")
                .auth(new AuthConfig(Require.NONE, null, null))
                .build();
        List<EndpointConfig> endpoints = List.of(endpointFor("MISSING"), second);

        TopologyResolutionException exception = assertThrows(TopologyResolutionException.class,
                () -> resolver.resolve(file, endpoints, List.of("MISSING")));

        assertEquals(1, exception.errors().size(), () -> "one refusal per alias, got " + exception.errors());
    }

    @Test
    @DisplayName("An unresolved additional alias stays skipped and is not reported beside an enabled-alias failure")
    void unresolvedAdditionalAliasIsNotReported() throws Exception {
        Path file = topologyFile("ORDERS=${ORDERS_URL}\n");
        TopologyResolver resolver = resolverWith(Map.of());
        List<EndpointConfig> endpoints = List.of(endpointFor("ORDERS"));

        TopologyResolutionException exception = assertThrows(TopologyResolutionException.class,
                () -> resolver.resolve(file, endpoints, List.of("ABSENT")));

        assertEquals(List.of("ORDERS"), exception.errors().stream().map(ConfigError::pointer).toList(),
                "only the enabled alias is refused; the absent additional alias is left to ConfigValidator");
    }

    @Test
    @DisplayName("A malformed additional alias URL is collected like an enabled one")
    void malformedAdditionalAliasIsReported() throws Exception {
        Path file = topologyFile("ORDERS=https://orders.internal\nSECURE=ftp://secure.internal\n");
        TopologyResolver resolver = resolverWith(Map.of());
        List<EndpointConfig> endpoints = List.of(endpointFor("ORDERS"));

        TopologyResolutionException exception = assertThrows(TopologyResolutionException.class,
                () -> resolver.resolve(file, endpoints, List.of("SECURE")));

        assertEquals(List.of(topologyError("SECURE",
                "Topology URL for alias 'SECURE' must use an http or https scheme, but was 'ftp'")),
                exception.errors());
    }

    @Test
    @DisplayName("An applied ${HOST:-default} on an enabled alias is reported by alias and variable name")
    void reportsDefaultedPlaceholderForResolvedAlias() throws Exception {
        Path file = topologyFile("ORDERS=https://${HOST:-localhost}:8443\n");
        TopologyResolver resolver = resolverWith(Map.of());
        List<DefaultedPlaceholder> defaulted = new ArrayList<>();

        ResolvedTopology topology = resolver.resolve(file, List.of(endpointFor("ORDERS")), List.of(),
                defaulted::add);

        assertEquals(List.of(new DefaultedPlaceholder("topology.properties", "ORDERS", "HOST")), defaulted);
        assertEquals("localhost", topology.lookup("ORDERS").orElseThrow().host());
    }

    @Test
    @DisplayName("A set variable on a defaulted placeholder reports no fallback")
    void reportsNothingWhenVariableIsSet() throws Exception {
        Path file = topologyFile("ORDERS=https://${HOST:-localhost}:8443\n");
        TopologyResolver resolver = resolverWith(Map.of("HOST", "orders.internal"));
        List<DefaultedPlaceholder> defaulted = new ArrayList<>();

        resolver.resolve(file, List.of(endpointFor("ORDERS")), List.of(), defaulted::add);

        assertTrue(defaulted.isEmpty(), () -> "no default applied, but got " + defaulted);
    }

    @Test
    @DisplayName("An alias referenced only by a disabled endpoint is never resolved, so never reported")
    void reportsNothingForAliasOfDisabledEndpoint() throws Exception {
        Path file = topologyFile("ORDERS=https://${HOST:-localhost}:8443\n");
        TopologyResolver resolver = resolverWith(Map.of());
        List<DefaultedPlaceholder> defaulted = new ArrayList<>();
        List<EndpointConfig> enabledEndpoints = List.of();

        ResolvedTopology topology = resolver.resolve(file, enabledEndpoints, List.of(), defaulted::add);

        assertAll("a disabled endpoint's alias is exempt from resolution and from reporting",
                () -> assertTrue(defaulted.isEmpty(), () -> "nothing was resolved, but got " + defaulted),
                () -> assertTrue(topology.aliases().isEmpty(), "the alias must not be resolved"));
    }

    @Test
    @DisplayName("A fallback is reported even when the same pass later fails")
    void reportsDefaultedPlaceholderEvenWhenPassFails() throws Exception {
        Path file = topologyFile("ORDERS=https://${HOST:-localhost}:8443\n");
        TopologyResolver resolver = resolverWith(Map.of());
        List<DefaultedPlaceholder> defaulted = new ArrayList<>();
        List<EndpointConfig> endpoints = List.of(endpointFor("ORDERS"), endpointFor("MISSING"));

        assertThrows(TopologyResolutionException.class,
                () -> resolver.resolve(file, endpoints, List.of(), defaulted::add));

        assertEquals(List.of(new DefaultedPlaceholder("topology.properties", "ORDERS", "HOST")), defaulted);
    }

    @Test
    @DisplayName("The default literal never reaches a reported fallback")
    void defaultLiteralNeverReachesReport() throws Exception {
        String sentinelHost = "sentinel" + Generators.letterStrings(6, 12).next().toLowerCase(Locale.ROOT);
        Path file = topologyFile("ORDERS=https://${HOST:-" + sentinelHost + "}:8443\n");
        TopologyResolver resolver = resolverWith(Map.of());
        List<DefaultedPlaceholder> defaulted = new ArrayList<>();

        resolver.resolve(file, List.of(endpointFor("ORDERS")), List.of(), defaulted::add);

        assertTrue(defaulted.stream().noneMatch(entry -> entry.toString().contains(sentinelHost)),
                () -> "the default literal must never be recorded: " + defaulted);
    }

    @Test
    void resolvesNothingWhenNoEnabledEndpointsAndToleratesAbsentFile() {
        Path absent = directory.resolve("does-not-exist.properties");

        ResolvedTopology topology = resolverWith(Map.of()).resolve(absent, List.of(), List.of());

        assertTrue(topology.aliases().isEmpty());
    }

    @Test
    void resolvesAdditionalAliasNotReferencedByAnyEnabledEndpoint() throws Exception {
        Path file = topologyFile("ORDERS=https://orders.internal\nSECURE=https://secure.internal:8443\n");

        ResolvedTopology topology = resolverWith(Map.of())
                .resolve(file, List.of(endpointFor("ORDERS")), List.of("SECURE"));

        ResolvedUpstream upstream = topology.lookup("SECURE").orElseThrow();
        assertEquals("secure.internal", upstream.host());
        assertEquals(8443, upstream.port());
    }

    @Test
    void skipsUnresolvableAdditionalAliasWithoutThrowing() throws Exception {
        Path file = topologyFile("ORDERS=https://orders.internal\n");
        TopologyResolver resolver = resolverWith(Map.of());
        List<EndpointConfig> endpoints = List.of(endpointFor("ORDERS"));

        ResolvedTopology topology = assertDoesNotThrow(
                () -> resolver.resolve(file, endpoints, List.of("MISSING")),
                "an unresolvable additional alias must be skipped, not thrown");

        assertAll("the unresolvable additional alias is omitted, leaving ConfigValidator to report it",
                () -> assertTrue(topology.lookup("MISSING").isEmpty(), "MISSING must be absent from the topology"),
                () -> assertTrue(topology.lookup("ORDERS").isPresent(), "ORDERS must still resolve"));
    }

    @Test
    void stillThrowsForUnresolvableEnabledEndpointAliasAlongsideSkippedAdditionalAlias() throws Exception {
        Path file = topologyFile("SECURE=https://secure.internal\n");
        TopologyResolver resolver = resolverWith(Map.of());
        List<EndpointConfig> endpoints = List.of(endpointFor("MISSING"));
        List<String> additionalAliases = List.of("SECURE");

        TopologyResolutionException exception = assertThrows(TopologyResolutionException.class,
                () -> resolver.resolve(file, endpoints, additionalAliases),
                "an unresolvable enabled-endpoint base_url alias must still fail the boot");

        assertEquals(List.of(topologyError("MISSING",
                "Unresolved topology alias 'MISSING' referenced by enabled endpoint 'missing'")),
                exception.errors());
    }

    @Test
    @DisplayName("An enabled endpoint declaring no base_url contributes no alias and never fails the resolution")
    void skipsEnabledEndpointWithoutBaseUrl() throws Exception {
        Path file = topologyFile("ORDERS=https://orders.internal\n");
        TopologyResolver resolver = resolverWith(Map.of());
        EndpointConfig assetOnly = EndpointConfig.builder()
                .id("site")
                .enabled(true)
                .auth(new AuthConfig(Require.NONE, null, null))
                .build();
        List<EndpointConfig> endpoints = List.of(assetOnly, endpointFor("ORDERS"));

        ResolvedTopology topology = assertDoesNotThrow(
                () -> resolver.resolve(file, endpoints, List.of()),
                "an endpoint without base_url has no alias to resolve; the conditional rule is the validator's");

        assertEquals(Set.of("ORDERS"), topology.aliases().keySet(),
                "only the declared alias is resolved — the base_url-less endpoint adds no entry");
    }

    @Test
    @DisplayName("A resolved alias URL with a non-http(s) scheme fails the boot (D5 scheme allowlist)")
    void rejectsNonHttpScheme() throws Exception {
        Path file = topologyFile("ORDERS=ftp://orders.internal:21\n");
        TopologyResolver resolver = resolverWith(Map.of());
        List<EndpointConfig> endpoints = List.of(endpointFor("ORDERS"));

        TopologyResolutionException exception = assertThrows(TopologyResolutionException.class,
                () -> resolver.resolve(file, endpoints, List.of()),
                "a non-http(s) topology alias scheme must be rejected at boot");

        assertEquals(List.of(topologyError("ORDERS",
                "Topology URL for alias 'ORDERS' must use an http or https scheme, but was 'ftp'")),
                exception.errors());
    }
}
