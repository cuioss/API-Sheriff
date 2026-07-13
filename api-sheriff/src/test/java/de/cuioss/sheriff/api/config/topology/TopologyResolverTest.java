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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cuioss.sheriff.api.config.model.AuthConfig;
import de.cuioss.sheriff.api.config.model.EndpointConfig;
import de.cuioss.sheriff.api.config.model.ResolvedTopology;
import de.cuioss.sheriff.api.config.model.ResolvedUpstream;
import de.cuioss.sheriff.api.config.topology.TopologyResolver.TopologyResolutionException;

/**
 * Tests for {@link TopologyResolver}: file / environment resolution precedence, URL
 * decomposition with scheme-default ports, and the boot failures for malformed or
 * missing aliases referenced by enabled endpoints.
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

        ResolvedTopology topology = resolverWith(Map.of()).resolve(file, List.of(endpointFor("ORDERS")));

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
                .resolve(file, List.of(endpointFor("USERS"), endpointFor("ORDERS")));

        assertEquals(443, topology.lookup("USERS").orElseThrow().port());
        assertEquals(80, topology.lookup("ORDERS").orElseThrow().port());
        assertEquals("", topology.lookup("USERS").orElseThrow().basePath());
    }

    @Test
    void environmentOverrideWinsOverFile() throws Exception {
        Path file = topologyFile("ORDERS=https://file.host:1000/file\n");

        ResolvedTopology topology = resolverWith(Map.of("TOPOLOGY_ORDERS", "https://env.host:2000/env"))
                .resolve(file, List.of(endpointFor("ORDERS")));

        ResolvedUpstream upstream = topology.lookup("ORDERS").orElseThrow();
        assertEquals("env.host", upstream.host());
        assertEquals(2000, upstream.port());
    }

    @Test
    void rejectsMalformedUrl() throws Exception {
        Path file = topologyFile("ORDERS=http://orders internal:8443\n");

        assertThrows(TopologyResolutionException.class,
                () -> resolverWith(Map.of()).resolve(file, List.of(endpointFor("ORDERS"))));
    }

    @Test
    void rejectsUrlWithoutSchemeOrHost() throws Exception {
        Path file = topologyFile("ORDERS=orders.internal:8443\n");

        assertThrows(TopologyResolutionException.class,
                () -> resolverWith(Map.of()).resolve(file, List.of(endpointFor("ORDERS"))));
    }

    @Test
    void rejectsMissingAliasReferencedByEnabledEndpoint() throws Exception {
        Path file = topologyFile("OTHER=https://other.internal\n");

        assertThrows(TopologyResolutionException.class,
                () -> resolverWith(Map.of()).resolve(file, List.of(endpointFor("MISSING"))));
    }

    @Test
    void resolvesNothingWhenNoEnabledEndpointsAndToleratesAbsentFile() {
        Path absent = directory.resolve("does-not-exist.properties");

        ResolvedTopology topology = resolverWith(Map.of()).resolve(absent, List.of());

        assertTrue(topology.aliases().isEmpty());
    }
}
