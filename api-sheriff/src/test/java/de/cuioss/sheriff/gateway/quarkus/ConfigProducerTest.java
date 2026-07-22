/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.gateway.quarkus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


import de.cuioss.sheriff.gateway.config.model.AssetConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.ResolvedAsset;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ConfigProducer}: CDI bean assembly from a valid configuration
 * directory, the memoized single build, eager startup assembly, and the
 * {@code CONFIG_LOADED} INFO log record.
 */
@EnableTestLogger
class ConfigProducerTest {

    private static final String VALID_GATEWAY = """
            version: 1
            metadata:
              config_version: "2026-07-13"
            """;

    private static final String PASSTHROUGH_HOST = "payments.example.com";
    private static final String PASSTHROUGH_ALIAS = "PAYMENTS_BACKEND";
    private static final String PASSTHROUGH_ORIGIN = "https://payments.internal:8443";

    private static final String GATEWAY_WITH_PASSTHROUGH_SNI = """
            version: 1
            metadata:
              config_version: "2026-07-13"
            tls:
              passthrough_sni:
                "%s": "%s"
            """.formatted(PASSTHROUGH_HOST, PASSTHROUGH_ALIAS);

    /**
     * A gateway whose single anchor violates the ADR-0013 access→auth matrix: a
     * {@code type: bff} anchor declared {@code access: public} (a bff surface must be
     * {@code access: authenticated}). The document is schema-valid — both required
     * classification axes are present — so the violation is caught by
     * {@link de.cuioss.sheriff.gateway.config.validation.ConfigValidator}, not the schema,
     * which is what makes it exercise the startup-event validation path.
     */
    private static final String GATEWAY_WITH_MATRIX_VIOLATION = """
            version: 1
            metadata:
              config_version: "2026-07-13"
            anchors:
              portal:
                path_prefix: /portal
                type: bff
                access: public
            """;

    /**
     * A gateway with a single {@code type: asset} / {@code access: public} anchor. Paired with an
     * endpoint whose route declares (or omits) an {@code asset} block, it exercises the ADR-0014
     * terminal-action consistency rules on the startup-event path: the documents are schema-valid,
     * so the violation is caught by {@code ConfigValidator}, not the schema.
     */
    private static final String GATEWAY_WITH_ASSET_ANCHOR = """
            version: 1
            metadata:
              config_version: "2026-07-13"
            anchors:
              assets:
                path_prefix: /assets
                type: asset
                access: public
            """;

    @TempDir
    Path configDir;

    private void writeTopology(String aliasLine) throws IOException {
        Files.writeString(configDir.resolve("topology.properties"), aliasLine);
    }

    private void writeEndpoint(String yaml) throws IOException {
        Files.createDirectories(configDir.resolve("endpoints"));
        Files.writeString(configDir.resolve("endpoints/web.yaml"), yaml);
    }

    private ConfigProducer producerForGateway(String gatewayYaml) throws IOException {
        Files.writeString(configDir.resolve("gateway.yaml"), gatewayYaml);
        ConfigProducer producer = new ConfigProducer();
        producer.configDir = configDir.toString();
        return producer;
    }

    private ConfigProducer producerForValidConfig() throws IOException {
        return producerForGateway(VALID_GATEWAY);
    }

    @Test
    void shouldProduceBeansFromValidConfig() throws Exception {
        ConfigProducer producer = producerForValidConfig();

        GatewayConfig gateway = producer.gatewayConfig();
        RouteTable routeTable = producer.routeTable();

        assertEquals(1, gateway.version(), "the bound gateway should carry the configured version");
        assertNotNull(routeTable, "the route table bean should be produced");
        assertTrue(routeTable.routes().isEmpty(), "a config with no endpoints yields an empty route table");
    }

    @Test
    void shouldLogConfigLoadedOnSuccess() throws Exception {
        ConfigProducer producer = producerForValidConfig();

        producer.gatewayConfig();

        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO, "Configuration loaded successfully");
    }

    @Test
    void shouldBuildOnceAndCacheTheResult() throws Exception {
        ConfigProducer producer = producerForValidConfig();

        GatewayConfig first = producer.gatewayConfig();
        GatewayConfig second = producer.gatewayConfig();

        assertSame(first, second, "the pipeline should be assembled once and cached");
    }

    @Test
    void shouldAssembleEagerlyOnStartupForValidConfig() throws Exception {
        ConfigProducer producer = producerForValidConfig();

        assertDoesNotThrow(() -> producer.onStartup(null),
                "a valid configuration should assemble without failing startup");
        assertNotNull(producer.gatewayConfig(), "beans should be available after startup assembly");
    }

    @Test
    void shouldNotFailBootWhenDisabledEndpointAliasIsUnresolvable() throws Exception {
        ConfigProducer producer = producerForValidConfig();
        Files.createDirectories(configDir.resolve("endpoints"));
        Files.writeString(configDir.resolve("endpoints/disabled.yaml"), """
                endpoint:
                  id: disabled
                  enabled: false
                  base_url: MISSING_ALIAS
                  auth:
                    require: none
                  routes:
                    - id: disabled-route
                      match:
                        path_prefix: /disabled
                        methods: ["GET"]
                """);

        assertDoesNotThrow(() -> producer.onStartup(null),
                "a disabled endpoint whose base_url alias resolves to nothing must not fail boot");
        assertTrue(producer.routeTable().routes().isEmpty(),
                "a disabled endpoint contributes no route to the assembled table");
    }

    /**
     * Pins the {@code tls.passthrough_sni} wiring at {@code ConfigProducer}: the map is
     * host -> alias, so the producer must hand the map's <em>values</em> (the aliases) to
     * {@link de.cuioss.sheriff.gateway.config.topology.TopologyResolver}'s
     * {@code additionalAliases}, never its {@code keySet()} (the SNI hosts).
     * <p>
     * The alias is resolvable via {@code topology.properties} while the host key is not a
     * topology alias at all, which is what makes this test discriminating: with the
     * correct {@code values()} wiring the alias resolves and the boot is clean, whereas a
     * {@code keySet()} swap would hand the resolver the host, leave the alias unresolved,
     * and trip {@code ConfigValidator}'s unresolved-passthrough-alias rule — failing this
     * test. Asserting on an <em>unresolvable</em> alias instead would be vacuous: the
     * resolver skips unresolved additional aliases silently, so the validator reports the
     * same violation under either wiring.
     */
    @Test
    void shouldResolvePassthroughSniAliasValueRatherThanHostKey() throws Exception {
        ConfigProducer producer = producerForGateway(GATEWAY_WITH_PASSTHROUGH_SNI);
        Files.writeString(configDir.resolve("topology.properties"),
                "%s=%s%n".formatted(PASSTHROUGH_ALIAS, PASSTHROUGH_ORIGIN));

        assertDoesNotThrow(() -> producer.onStartup(null),
                "the passthrough_sni alias value must reach the topology resolver and resolve");
        assertNotNull(producer.gatewayConfig(), "beans should be available after startup assembly");
    }

    @Test
    void shouldFailBootWhenPassthroughSniAliasIsUnresolvable() throws Exception {
        ConfigProducer producer = producerForGateway(GATEWAY_WITH_PASSTHROUGH_SNI);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> producer.onStartup(null),
                "an unresolvable passthrough_sni alias should abort startup");

        assertTrue(exception.getMessage().contains("Refusing to start"),
                "the abort should carry the refusing-to-start summary");
    }

    /**
     * The fail-closed access→auth matrix (ADR-0013) must be enforced on the eager
     * startup-event path, not only through a direct {@code ConfigValidator} call: a
     * matrix-violating anchor aborts boot through {@code onStartup} → {@code buildOnce}
     * → {@code ConfigValidator.validate}. The bff+public anchor is schema-valid, so
     * reaching the abort proves the validator ran at startup, and the annotated ERROR
     * record confirms it was the matrix rule that tripped.
     */
    @Test
    void shouldFailBootThroughStartupEventWhenAccessAuthMatrixIsViolated() throws Exception {
        ConfigProducer producer = producerForGateway(GATEWAY_WITH_MATRIX_VIOLATION);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> producer.onStartup(null),
                "a matrix-violating anchor must abort boot on the startup-event path");

        assertTrue(exception.getMessage().contains("Refusing to start"),
                "the abort should carry the refusing-to-start summary");
        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.ERROR,
                "is type 'bff' and must declare access: authenticated");
    }

    /**
     * The ADR-0014 terminal-action consistency rules must fire on the eager startup-event path: an
     * asset-type anchor whose route declares no {@code asset} block aborts boot through
     * {@code onStartup} → {@code buildOnce} → {@code ConfigValidator.validate}. The documents are
     * schema-valid (the route is a well-formed proxy route), so reaching the abort proves the
     * validator ran at startup, and the annotated ERROR record confirms the terminal-action rule
     * tripped.
     */
    @Test
    void shouldFailBootThroughStartupEventWhenAssetAnchorRouteHasNoAssetAction() throws Exception {
        ConfigProducer producer = producerForGateway(GATEWAY_WITH_ASSET_ANCHOR);
        writeTopology("WEB_BACKEND=https://web.internal:8443\n");
        writeEndpoint("""
                endpoint:
                  id: web
                  enabled: true
                  base_url: WEB_BACKEND
                  anchor: assets
                  auth:
                    require: none
                  routes:
                    - id: noasset
                      match:
                        path_prefix: /assets
                        methods: ["GET"]
                """);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> producer.onStartup(null),
                "an asset-anchor route with no asset action must abort boot");

        assertTrue(exception.getMessage().contains("Refusing to start"),
                "the abort should carry the refusing-to-start summary");
        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.ERROR, "declares no asset terminal action");
    }

    /**
     * A valid asset route assembles cleanly through the startup path, and its terminal action is
     * materialized onto the resolved route — the end-to-end YAML → {@code AssetConfig} →
     * {@code ResolvedAsset} binding through {@code onStartup}.
     */
    @Test
    void shouldAssembleAssetRouteThroughStartupEvent() throws Exception {
        ConfigProducer producer = producerForGateway(GATEWAY_WITH_ASSET_ANCHOR);
        writeTopology("WEB_BACKEND=https://web.internal:8443\n");
        writeEndpoint("""
                endpoint:
                  id: web
                  enabled: true
                  base_url: WEB_BACKEND
                  anchor: assets
                  auth:
                    require: none
                  routes:
                    - id: bundle
                      match:
                        path_prefix: /assets
                        methods: ["GET"]
                      asset:
                        source: directory
                        directory: /srv/assets
                """);

        assertDoesNotThrow(() -> producer.onStartup(null),
                "a valid asset route must assemble without failing startup");
        RouteTable table = producer.routeTable();
        assertEquals(1, table.routes().size(), "the asset route is merged into the table");
        assertTrue(table.routes().getFirst().asset().isPresent(),
                "the asset terminal action is materialized onto the resolved route");
    }

    /**
     * A {@code source: upstream} asset route (the {@code /assets/cdn} shape from the integration
     * config) whose upstream alias is declared in {@code topology.properties} must assemble cleanly
     * through the startup path. An asset route's upstream is a per-route topology reference that the
     * proxy-oriented {@code base_url} collection does not see, so {@code ConfigProducer} has to gather
     * it into the topology-resolution set — otherwise a well-formed upstream asset route is rejected
     * as unresolved. Asserting the decomposed upstream is materialized onto the route proves the alias
     * reached the resolver and resolved (ADR-0014).
     */
    @Test
    void shouldAssembleUpstreamAssetRouteWhenAliasIsDeclaredInTopology() throws Exception {
        ConfigProducer producer = producerForGateway(GATEWAY_WITH_ASSET_ANCHOR);
        writeTopology("""
                WEB_BACKEND=https://web.internal:8443
                ASSET_ORIGIN=http://asset-origin:80
                """);
        writeEndpoint("""
                endpoint:
                  id: web
                  enabled: true
                  base_url: WEB_BACKEND
                  anchor: assets
                  auth:
                    require: none
                  routes:
                    - id: assets-upstream
                      match:
                        path_prefix: /assets
                      asset:
                        source: upstream
                        upstream: ASSET_ORIGIN
                """);

        assertDoesNotThrow(() -> producer.onStartup(null),
                "an upstream asset route whose alias is declared in the topology must assemble without failing startup");
        RouteTable table = producer.routeTable();
        assertEquals(1, table.routes().size(), "the upstream asset route is merged into the table");
        ResolvedAsset asset = table.routes().getFirst().asset()
                .orElseThrow(() -> new AssertionError("the asset terminal action is materialized onto the resolved route"));
        assertEquals(AssetConfig.Source.UPSTREAM, asset.source(), "the resolved asset carries the upstream source");
        ResolvedUpstream upstream = asset.upstream()
                .orElseThrow(() -> new AssertionError("the ASSET_ORIGIN alias resolved onto the asset action"));
        assertEquals("asset-origin", upstream.host(), "the ASSET_ORIGIN alias decomposed to the declared host");
        assertEquals(80, upstream.port(), "the ASSET_ORIGIN alias decomposed to the declared port");
    }

    /**
     * The exact failure CI surfaced: a {@code source: upstream} asset route whose upstream alias is
     * NOT declared in {@code topology.properties} must abort boot on the startup-event path with the
     * ADR-0014 topology-resolution violation. The endpoint's own {@code base_url} alias IS declared,
     * so the sole unresolved reference is the asset upstream — proving the asset alias is validated
     * against the resolved topology, not merely the endpoints' {@code base_url} set, and that the
     * violation is reported through {@code onStartup} → {@code buildOnce} → {@code ConfigValidator}.
     */
    @Test
    void shouldFailBootThroughStartupEventWhenUpstreamAssetAliasIsUndeclared() throws Exception {
        ConfigProducer producer = producerForGateway(GATEWAY_WITH_ASSET_ANCHOR);
        writeTopology("WEB_BACKEND=https://web.internal:8443\n");
        writeEndpoint("""
                endpoint:
                  id: web
                  enabled: true
                  base_url: WEB_BACKEND
                  anchor: assets
                  auth:
                    require: none
                  routes:
                    - id: assets-upstream
                      match:
                        path_prefix: /assets
                      asset:
                        source: upstream
                        upstream: ASSET_ORIGIN
                """);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> producer.onStartup(null),
                "an upstream asset route whose alias is absent from the topology must abort boot");

        assertTrue(exception.getMessage().contains("Refusing to start"),
                "the abort should carry the refusing-to-start summary");
        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.ERROR,
                "upstream alias 'ASSET_ORIGIN' does not resolve in the topology");
    }
}
