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
package de.cuioss.sheriff.gateway.config.validation.rule;

import static de.cuioss.sheriff.gateway.testsupport.ConfigFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AssetDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.CatalogConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardedConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.IssuerConfig;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.RedirectConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.config.model.TokenValidationConfig;
import de.cuioss.sheriff.gateway.config.model.WebSocketConfig;
import de.cuioss.sheriff.gateway.config.validation.ConfigValidator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The CWE-117 contract {@link RuleSupport} states for every boot refusal: a configured free-form scalar
 * a refusal echoes — an endpoint or route id, an anchor name, an alias, a path, a host, a list entry —
 * reaches the message through {@link RuleSupport#renderForMessage}, so a control character in it is
 * rendered as a {@code \\uXXXX} escape and cannot forge a boot log line.
 * <p>
 * One case per rule class of this package that echoes such a scalar. Each case plants a control
 * character in exactly one configured value, drives the full default {@link ConfigValidator} — so a
 * refusal lost from its rule's registration fails here as surely as an unescaped echo — and asserts two
 * things: the refusal the case targets carries the escaped rendering, and <em>no</em> refusal of the
 * whole run carries a raw control character, whichever rule reported it.
 * <p>
 * The table is not trusted to name the echoing classes. {@code shouldCoverEveryEchoingRuleClass}
 * derives them from the compiled classes — every class of this package other than {@link RuleSupport}
 * whose bytecode calls {@link RuleSupport#renderForMessage} — and requires the set of row keys to equal
 * it, so a rule class that starts echoing without a row, or a row whose class was renamed or stopped
 * echoing, fails the build. Each case targets one echo of its class; a class with several echo sites
 * is covered at one of them, and the run-wide raw-character assertion holds every other echo the
 * planted value happens to reach. {@link AnchorRules} carries a second row because it echoes two
 * different configured scalars, an anchor reference and an anchor {@code path_prefix}.
 * <p>
 * The values are written directly into the bound model, bypassing the schema on purpose: the
 * endpoint and route ids are pattern-bound at load, but the escaping is the rules' own guarantee and
 * must not depend on a stage that runs before them.
 */
@EnableGeneratorController
@DisplayName("ConfigValidator — every echoed configuration scalar is rendered escaped")
class RefusalEchoEscapingTest {

    private static final char LINE_FEED = '\n';
    private static final char CARRIAGE_RETURN = '\r';
    private static final char ESCAPE = (char) 0x1B;
    private static final char BELL = (char) 0x07;

    /** The package whose rule classes the coverage check derives from the compiled classes. */
    private static final String RULE_PACKAGE = RuleSupport.class.getPackageName();

    /** The name of the single CWE-117 escaper every echoing rule class calls. */
    private static final String RENDER_FOR_MESSAGE = "renderForMessage";

    private final ConfigValidator validator = new ConfigValidator();

    /**
     * @param ruleClass   the rule class whose refusal the case targets, for the display name
     * @param gateway     the gateway document
     * @param endpoints   the enabled endpoints
     * @param topology    the resolved topology
     * @param pointer     a fragment of the targeted refusal's JSON pointer
     * @param escapedEcho the escaped rendering the targeted refusal must carry
     * @return the case
     */
    private static Arguments refusal(String ruleClass, GatewayConfig gateway, List<EndpointConfig> endpoints,
            ResolvedTopology topology, String pointer, String escapedEcho) {
        return Arguments.of(ruleClass, gateway, endpoints, topology, pointer, escapedEcho);
    }

    /** A plain proxy route under {@code /orders} carrying the given id, declaring nothing else. */
    private static RouteConfig.RouteConfigBuilder routeWithId(String id) {
        return RouteConfig.builder().id(id).match(match("/orders"));
    }

    /** A {@code require: none} endpoint {@code orders} on the alias {@code ORDERS} carrying one route. */
    private static List<EndpointConfig> openOrders(RouteConfig route) {
        return List.of(openEndpointSeed("orders", "ORDERS").routes(List.of(route)).build());
    }

    static Stream<Arguments> echoingRefusals() {
        String forgedRouteId = "orders" + LINE_FEED + "X-Forged: 1";
        String escapedRouteId = "orders\\u000AX-Forged: 1";
        String crRouteId = "orders" + CARRIAGE_RETURN + "read";
        String escapedCrRouteId = "orders\\u000Dread";
        return Stream.of(
                refusal("AnchorRules", validGateway().build(),
                        openOrders(routeWithId("orders-read").anchor("ghost" + ESCAPE + "[2J").build()),
                        topologyWith("ORDERS"), "/endpoint/routes", "references undefined anchor 'ghost\\u001B[2J'"),
                // The anchor path_prefix is echoed beside the anchor name; the pair overlaps, and the
                // fragment holds whichever order the anchors map iterates in.
                refusal("AnchorRules", gatewayWithAnchors(Map.of(
                        "api", anchor("api", "/api", null),
                        "nested", anchor("nested", "/api/v" + LINE_FEED + "1", null))),
                        List.of(), topologyWith(), "/anchors", "(/api/v\\u000A1)"),
                refusal("AuthRules", validGateway().build(),
                        List.of(endpointSeed("orders").baseUrl("ORDERS")
                                .routes(List.of(routeWithId(crRouteId).build())).build()),
                        topologyWith("ORDERS"), "/endpoint/routes",
                        "route '" + escapedCrRouteId + "' has no resolvable auth"),
                refusal("CookieRules", validGateway().build(),
                        List.of(openEndpointSeed("orders", null).routes(List.of(RouteConfig.builder()
                                .id(forgedRouteId).match(MatchConfig.builder().path("/old").build())
                                .redirect(new RedirectConfig("/new", 308, false, false))
                                .cookies(cookies("theme")).build())).build()),
                        topologyWith(), "/endpoint/routes",
                        "route '" + escapedRouteId + "' declares a cookies block but serves an asset or a redirect"),
                refusal("EdgeRules", validGateway()
                                .forwarded(new ForwardedConfig(List.of("10.0.0.0/8" + LINE_FEED + "forged"), null))
                                .build(),
                        List.of(), topologyWith(), "/forwarded",
                        "malformed trusted_proxies CIDR: 10.0.0.0/8\\u000Aforged"),
                refusal("LimitRules", validGateway().build(),
                        List.of(endpointSeed("orders").baseUrl("ORDERS").auth(auth(Require.BEARER))
                                .routes(List.of(routeWithId(forgedRouteId).securityFilter(filter("minimal")).build()))
                                .build()),
                        topologyWith("ORDERS"), "/endpoint/routes",
                        "route '" + escapedRouteId + "' resolves inbound-filter profile 'minimal'"),
                refusal("ProtocolRules", validGateway().build(),
                        openOrders(routeWithId("orders-socket").protocol(Protocol.WEBSOCKET)
                                .websocket(new WebSocketConfig(List.of("https://*.example" + ESCAPE + "[2J"), null))
                                .build()),
                        topologyWith("ORDERS"), "/endpoint/routes",
                        "allowed_origins entry 'https://*.example\\u001B[2J' must be an exact origin"),
                refusal("TerminalActionRules", validGateway().build(),
                        List.of(openEndpointSeed("orders", null).routes(List.of(RouteConfig.builder()
                                .id(forgedRouteId).match(MatchConfig.builder().path("/old").build())
                                .redirect(new RedirectConfig("/new", 308, false, false))
                                .asset(directoryAsset("/srv/static"))
                                .build())).build()),
                        topologyWith(), "/endpoint/routes",
                        "route '" + escapedRouteId + "' declares both a redirect and an asset terminal action"),
                refusal("TopologyRules", validGateway().build(),
                        List.of(openEndpointSeed("orders", "MISS" + LINE_FEED + "ING")
                                .routes(List.of(routeWithId("orders-read").build())).build()),
                        topologyWith(), "/endpoint/base_url", "unresolved topology alias: MISS\\u000AING"),
                refusal("AssetDefaultsRules", validGateway()
                                .assetDefaults(new AssetDefaultsConfig(Map.of("we" + BELL + "ird", "no media type")))
                                .build(),
                        List.of(), topologyWith(), "/asset_defaults",
                        "asset_defaults.content_types maps 'we\\u0007ird' to 'no media type'"),
                refusal("ForwardingRules", validGateway().build(),
                        openOrders(routeWithId(forgedRouteId)
                                .auth(AuthConfig.builder().require(Require.NONE).tokenRelay(false).build())
                                .forward(ForwardConfig.builder().headersAllow(List.of("Authorization")).build())
                                .build()),
                        topologyWith("ORDERS"), "/endpoint/routes",
                        "route '" + escapedRouteId + "' resolves auth.token_relay: false"),
                refusal("ReservedPathRules", validGateway().oidc(OidcConfig.builder()
                                .userInfo(OidcConfig.UserInfo.builder().path("/session/userinfo")
                                        .allowedClaims(List.of("sub"))
                                        .defaultView(List.of("sub", "em" + LINE_FEED + "ail")).build())
                                .build()).build(),
                        List.of(), topologyWith(), "/oidc/user_info/default_view",
                        "default_view claim 'em\\u000Aail' is not in allowed_claims"),
                refusal("HeaderRules", validGateway()
                                .securityHeaders(SecurityHeadersConfig.builder()
                                        .contentSecurityPolicy("default-src 'self'" + LINE_FEED + "X-Forged: 1").build())
                                .build(),
                        List.of(), topologyWith(), "/security_headers/content_security_policy",
                        "content_security_policy default-src 'self'\\u000AX-Forged: 1 must be"),
                refusal("RouteMatchingRules", validGateway().build(),
                        List.of(openEndpointSeed("orders", "ORDERS").allowedMethods(List.of(HttpMethod.GET))
                                .routes(List.of(RouteConfig.builder().id(forgedRouteId)
                                        .match(match("/orders", HttpMethod.POST)).build()))
                                .build()),
                        topologyWith("ORDERS"), "/endpoint/routes",
                        "route '" + escapedRouteId + "' matches method POST outside the effective allowed_methods"),
                refusal("OidcClientRules", validGateway().oidc(OidcConfig.builder()
                                .issuer("https://idp.example/realms/sheriff")
                                .redirectUri("https://gw.example/bff/callback")
                                .allowedEgressHosts(List.of("idp" + BELL + ".example:8443")).build()).build(),
                        List.of(), topologyWith(), "/oidc/allowed_egress_hosts",
                        "oidc.allowed_egress_hosts entry 'idp\\u0007.example:8443' is refused"),
                refusal("OidcSessionRules", validGateway().oidc(OidcConfig.builder()
                                .session(OidcConfig.Session.builder().mode(OidcConfig.Session.MODE_COOKIE)
                                        .cookieName("__Host-se" + CARRIAGE_RETURN + "ss").build())
                                .build()).build(),
                        List.of(), topologyWith(), "/oidc/session/cookie_name",
                        "oidc session cookie_name '__Host-se\\u000Dss'"),
                refusal("PortalRules", validGateway().build(),
                        List.of(openEndpointSeed("orders" + ESCAPE + "[2J", "ORDERS")
                                .catalog(new CatalogConfig("Orders", null, "https://elsewhere.example/", null))
                                .routes(List.of(routeWithId("orders-read").build())).build()),
                        topologyWith("ORDERS"), "/endpoint/catalog/entry",
                        "catalog.entry of endpoint 'orders\\u001B[2J' points outside the gateway's own origin"),
                refusal("TokenValidationRules", validGateway()
                                .tokenValidation(new TokenValidationConfig(List.of(IssuerConfig.builder().name("corp")
                                        .issuer("https://corp.example")
                                        .jwks(IssuerConfig.Jwks.builder().source("http").url("https://corp.example/jwks")
                                                .allowedEgressHosts(List.of("idp" + BELL + ".example:8443")).build())
                                        .build())))
                                .build(),
                        List.of(), topologyWith(), "/token_validation/issuers/0/jwks/allowed_egress_hosts",
                        "jwks.allowed_egress_hosts entry 'idp\\u0007.example:8443' is refused"));
    }

    /**
     * The rule classes the table must cover, derived from the compiled classes rather than trusted from
     * the table: every class of this package, other than {@link RuleSupport} itself, whose bytecode calls
     * or references {@link RuleSupport#renderForMessage}. A nested class or a lambda body counts for the
     * top-level class that declares it, so the result is a set of simple top-level class names — the
     * spelling {@link #echoingRefusals()} uses as its row key.
     *
     * @return the simple names of the echoing rule classes
     */
    private static Set<String> echoingRuleClassesFromBytecode() {
        JavaClasses ruleClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(RULE_PACKAGE);
        return ruleClasses.stream()
                .filter(javaClass -> RULE_PACKAGE.equals(javaClass.getPackageName()))
                .filter(RefusalEchoEscapingTest::callsRenderForMessage)
                .map(RefusalEchoEscapingTest::topLevelSimpleName)
                .filter(name -> !RuleSupport.class.getSimpleName().equals(name))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static boolean callsRenderForMessage(JavaClass javaClass) {
        return javaClass.getMethodCallsFromSelf().stream()
                .anyMatch(call -> isRenderForMessage(call.getTarget().getOwner(), call.getTarget().getName()))
                || javaClass.getMethodReferencesFromSelf().stream()
                .anyMatch(reference -> isRenderForMessage(reference.getTarget().getOwner(),
                        reference.getTarget().getName()));
    }

    private static boolean isRenderForMessage(JavaClass owner, String methodName) {
        return owner.isEquivalentTo(RuleSupport.class) && RENDER_FOR_MESSAGE.equals(methodName);
    }

    private static String topLevelSimpleName(JavaClass javaClass) {
        String binaryName = javaClass.getName().substring(RULE_PACKAGE.length() + 1);
        int nested = binaryName.indexOf('$');
        return nested < 0 ? binaryName : binaryName.substring(0, nested);
    }

    @Test
    @DisplayName("Should hold exactly one escaping row per rule class whose bytecode echoes through renderForMessage")
    void shouldCoverEveryEchoingRuleClass() {
        // Arrange
        Set<String> derived = echoingRuleClassesFromBytecode();

        // Act
        Set<String> covered = echoingRefusals().map(arguments -> (String) arguments.get()[0])
                .collect(Collectors.toCollection(TreeSet::new));

        // Assert
        Set<String> uncovered = new TreeSet<>(derived);
        uncovered.removeAll(covered);
        Set<String> unknown = new TreeSet<>(covered);
        unknown.removeAll(derived);
        assertAll("echoing rule classes",
                () -> assertFalse(derived.isEmpty(),
                        "the bytecode scan must find echoing rule classes at all, or the coverage check is vacuous"),
                () -> assertTrue(derived.contains(AnchorRules.class.getSimpleName()),
                        () -> "the bytecode scan must find a class known to echo, got: " + derived),
                () -> assertTrue(uncovered.isEmpty(),
                        () -> "rule classes that echo through renderForMessage without an escaping row: " + uncovered),
                () -> assertTrue(unknown.isEmpty(),
                        () -> "escaping rows naming no echoing rule class (renamed or no longer echoing): " + unknown));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("echoingRefusals")
    @DisplayName("Should render a planted control character escaped, and no refusal of the run raw")
    void shouldRenderEveryEchoedScalarEscaped(String ruleClass, GatewayConfig gateway, List<EndpointConfig> endpoints,
            ResolvedTopology topology, String pointer, String escapedEcho) {
        // Act
        List<ConfigError> errors = validator.validate(gateway, endpoints, topology);

        // Assert
        assertAll(ruleClass,
                () -> assertTrue(errors.stream().anyMatch(error -> error.pointer().contains(pointer)
                                && error.message().contains(escapedEcho)),
                        () -> "expected a refusal under '" + pointer + "' echoing '" + escapedEcho + "', got: "
                                + errors),
                () -> assertTrue(errors.stream().allMatch(error -> error.message().chars()
                                .noneMatch(Character::isISOControl)),
                        () -> "a refusal echoed a control character raw: " + errors));
    }

    /**
     * The matched control for the escaping assertion: the escape is applied only to what needs it, so a
     * value made of printable ASCII reaches the refusal exactly as the operator wrote it. Without this
     * case a rule that blanked or mangled every echoed value would pass the table above.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("printableEchoes")
    @DisplayName("Should echo a printable value verbatim")
    void shouldEchoAPrintableValueVerbatim(String ruleClass, GatewayConfig gateway, List<EndpointConfig> endpoints,
            ResolvedTopology topology, String pointer, String verbatimEcho) {
        // Act
        List<ConfigError> errors = validator.validate(gateway, endpoints, topology);

        // Assert
        assertTrue(errors.stream().anyMatch(error -> error.pointer().contains(pointer)
                        && error.message().contains(verbatimEcho)),
                () -> ruleClass + ": expected a refusal under '" + pointer + "' echoing '" + verbatimEcho
                        + "' verbatim, got: " + errors);
    }

    static Stream<Arguments> printableEchoes() {
        return Stream.of(
                refusal("TopologyRules", validGateway().build(),
                        List.of(openEndpointSeed("orders", "MISSING")
                                .routes(List.of(routeWithId("orders-read").build())).build()),
                        topologyWith(), "/endpoint/base_url", "unresolved topology alias: MISSING"),
                refusal("AnchorRules", validGateway().build(),
                        openOrders(routeWithId("orders-read").anchor("ghost").build()),
                        topologyWith("ORDERS"), "/endpoint/routes",
                        "route 'orders-read' references undefined anchor 'ghost'"),
                refusal("AssetDefaultsRules", validGateway()
                                .assetDefaults(new AssetDefaultsConfig(Map.of("weird", "no media type")))
                                .build(),
                        List.of(), topologyWith(), "/asset_defaults",
                        "asset_defaults.content_types maps 'weird' to 'no media type'"));
    }
}
