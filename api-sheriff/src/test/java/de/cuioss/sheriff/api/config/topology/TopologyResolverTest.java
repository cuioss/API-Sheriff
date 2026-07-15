/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.config.topology;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import de.cuioss.sheriff.api.config.model.AuthConfig;
import de.cuioss.sheriff.api.config.model.EndpointConfig;
import de.cuioss.sheriff.api.config.model.ResolvedTopology;
import de.cuioss.sheriff.api.config.model.ResolvedUpstream;
import de.cuioss.sheriff.api.config.topology.TopologyResolver.TopologyResolutionException;

/**
 * Tests for {@link TopologyResolver}: file / environment resolution precedence, URL
 * decomposition with scheme-default ports, the boot failures for malformed or
 * missing aliases referenced by enabled endpoints, and the throw/skip asymmetry
 * between enabled-endpoint {@code base_url} aliases and additional aliases.
 */
class TopologyResolverTest {

    @TempDir
    Path directory;

    private static EndpointConfig endpointFor(String alias) {
        return EndpointConfig.builder()
                .id(alias.toLowerCase(Locale.ROOT))
                .enabled(true)
                .baseUrl(alias)
                .auth(new AuthConfig("none", List.of()))
                .build();
    }

    private Path topologyFile(String content) throws IOException {
        Path file = directory.resolve("topology.properties");
        Files.writeString(file, content);
        return file;
    }

    private static TopologyResolver resolverWith(Map<String, String> environment) {
        return new TopologyResolver(environment::get);
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
    void environmentOverrideWinsOverFile() throws Exception {
        Path file = topologyFile("ORDERS=https://file.host:1000/file\n");

        ResolvedTopology topology = resolverWith(Map.of("TOPOLOGY_ORDERS", "https://env.host:2000/env"))
                .resolve(file, List.of(endpointFor("ORDERS")), List.of());

        ResolvedUpstream upstream = topology.lookup("ORDERS").orElseThrow();
        assertEquals("env.host", upstream.host());
        assertEquals(2000, upstream.port());
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
    void rejectsInvalidTopology(String topologyContent, String scenario, String endpointAlias) throws Exception {
        Path file = topologyFile(topologyContent);
        TopologyResolver resolver = resolverWith(Map.of());
        List<EndpointConfig> endpoints = List.of(endpointFor(endpointAlias));

        assertThrows(TopologyResolutionException.class,
                () -> resolver.resolve(file, endpoints, List.of()));
    }

    static Stream<Arguments> invalidTopologies() {
        return Stream.of(
                Arguments.of("ORDERS=http://orders internal:8443\n", "malformed url", "ORDERS"),
                Arguments.of("ORDERS=orders.internal:8443\n", "url without scheme or host", "ORDERS"),
                Arguments.of("OTHER=https://other.internal\n", "missing alias referenced by enabled endpoint",
                        "MISSING"));
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

        assertThrows(TopologyResolutionException.class,
                () -> resolver.resolve(file, endpoints, List.of("SECURE")),
                "an unresolvable enabled-endpoint base_url alias must still fail the boot");
    }
}
