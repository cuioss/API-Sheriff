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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AnchorType;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.IssuerConfig;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.TokenValidationConfig;
import de.cuioss.sheriff.gateway.config.validation.ConfigValidator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.generator.junit.parameterized.GeneratorType;
import de.cuioss.test.generator.junit.parameterized.GeneratorsSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for the anchor rules the {@link ConfigValidator} runs through {@link AnchorRules}: the
 * ADR-0007 anchor rules — pairwise-disjoint prefixes, declared-anchor existence, namespace membership
 * (rules 3 and 4), namespace coverage (rule 4b) and the non-weakenable auth floor (rule 5) — the
 * scoped gRPC exemption from rules 3 and 4, and the ADR-0013 fail-closed access→auth matrix.
 * <p>
 * Every case drives the full default validator, so a rule dropped from its registration fails here as
 * surely as a rule whose body is removed.
 */
@EnableGeneratorController
@DisplayName("ConfigValidator — anchors")
class AnchorRulesTest {

    private final ConfigValidator validator = new ConfigValidator();

    // --- fixture helpers -------------------------------------------------

    private static GatewayConfig.GatewayConfigBuilder validGateway() {
        return GatewayConfig.builder().version(1);
    }

    private static AuthConfig auth(Require require) {
        return AuthConfig.builder().require(require).build();
    }

    private static ResolvedTopology topologyWith(String... aliases) {
        Map<String, ResolvedUpstream> map = new HashMap<>();
        for (String alias : aliases) {
            map.put(alias, new ResolvedUpstream("https", alias.toLowerCase(Locale.ROOT) + ".internal", 443, ""));
        }
        return new ResolvedTopology(map);
    }

    private static MatchConfig match(String pathPrefix, HttpMethod... methods) {
        return MatchConfig.builder().pathPrefix(pathPrefix).methods(List.of(methods)).build();
    }

    private static void assertHasError(List<ConfigError> errors, String pointerContains, String messageContains) {
        assertTrue(errors.stream()
                        .anyMatch(e -> e.pointer().contains(pointerContains) && e.message().contains(messageContains)),
                () -> "expected an error whose pointer contains '" + pointerContains + "' and message contains '"
                        + messageContains + "', but got: " + errors);
    }

    private static AnchorConfig anchor(String name, String prefix, @Nullable Require require) {
        // The ADR-0007 anchor rules (prefix disjointness, namespace membership, auth floor) are
        // orthogonal to the ADR-0013 access->auth matrix, so these fixtures stay matrix-consistent
        // by construction: an anchor with no auth floor is access: public (public + no auth block is
        // matrix-clean), while an anchor carrying a floor is access: authenticated (a non-'none'
        // floor is what access: authenticated requires).
        return AnchorConfig.builder()
                .name(name)
                .pathPrefix(prefix)
                .type(AnchorType.PROXY)
                .access(require == null ? AccessLevel.PUBLIC : AccessLevel.AUTHENTICATED)
                .auth(require == null ? null : auth(require))
                .build();
    }

    private static AnchorConfig matrixAnchor(String name, String prefix, AnchorType type, AccessLevel access,
            @Nullable Require require) {
        return AnchorConfig.builder()
                .name(name)
                .pathPrefix(prefix)
                .type(type)
                .access(access)
                .auth(require == null ? null : auth(require))
                .build();
    }

    private static GatewayConfig gatewayWithAnchorAndIssuer(AnchorConfig anchorConfig) {
        return validGateway()
                .anchors(Map.of(anchorConfig.name(), anchorConfig))
                .tokenValidation(new TokenValidationConfig(List.of(
                        IssuerConfig.builder().name("main").issuer("https://idp.example").build())))
                .build();
    }

    private static GatewayConfig gatewayWithAnchors(Map<String, AnchorConfig> anchors) {
        return validGateway().anchors(anchors).build();
    }

    private static EndpointConfig anchoredEndpoint(String id, String alias, String anchorName,
            @Nullable AuthConfig auth, RouteConfig... routes) {
        return EndpointConfig.builder()
                .id(id)
                .enabled(true)
                .baseUrl(alias)
                .anchor(anchorName)
                .auth(auth)
                .routes(List.of(routes))
                .build();
    }

    private static RouteConfig anchoredRoute(String id, String prefix, String anchorName, HttpMethod... methods) {
        return RouteConfig.builder()
                .id(id)
                .anchor(anchorName)
                .match(match(prefix, methods))
                .build();
    }

    /** An exact ({@code match.path}) route — the AS-2 matcher form the coverage rule reasons about. */
    private static RouteConfig exactRoute(String id, String path, @Nullable String anchorName) {
        return RouteConfig.builder()
                .id(id)
                .anchor(anchorName)
                .match(MatchConfig.builder().path(path).build())
                .build();
    }

    @Nested
    @DisplayName("The anchor namespace and auth-floor rules (ADR-0007)")
    class AnchorNamespaceAndFloor {

        @Test
        @DisplayName("Rule 1: Should reject anchor prefixes where one contains another")
        void shouldRejectOverlappingAnchorPrefixes() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "api", anchor("api", "/api", null),
                    "apiv1", anchor("apiv1", "/api/v1", null)));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/anchors", "pairwise disjoint");
        }

        @Test
        @DisplayName("Rule 2: Should reject a reference to an undefined anchor")
        void shouldRejectUndefinedAnchorReference() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("api", anchor("api", "/api", null)));
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "ghost",
                    auth(Require.NONE),
                    anchoredRoute("r", "/other", null, HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/endpoint/anchor", "references undefined anchor 'ghost'");
        }

        @Test
        @DisplayName("Rule 3: Should reject a route whose path lies outside its declared anchor namespace")
        void shouldRejectRoutePathOutsideDeclaredAnchor() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("api", anchor("api", "/api", null)));
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "api",
                    auth(Require.NONE),
                    anchoredRoute("r", "/billing", "api", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/endpoint/routes", "is not inside its declared anchor 'api'");
        }

        @Test
        @DisplayName("Rule 4: Should reject an undeclared squatter route inside an anchor namespace")
        void shouldRejectUndeclaredSquatter() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("api", anchor("api", "/api", null)));
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", null,
                    auth(Require.NONE),
                    anchoredRoute("r", "/api/secret", null, HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/endpoint/routes", "does not declare it");
        }

        // --- Rule 4b: the namespace-coverage mirror of rule 4 --------------------------------------
        // Rules 3 and 4 judge a route by where its OWN match key sits, so a route sitting outside and
        // ABOVE an anchor is unjudged by both. With AS-2 exact routes that gap is reachable: an exact
        // route covers one address, so every other address in the namespace falls through to the
        // broader route and is served with ITS auth posture. These four cases pin the rule and its
        // coverage exception, with the covered case as the negative control.

        @Test
        @DisplayName("Rule 4b: Should reject a broader route swallowing a namespace whose only member is an exact route")
        void shouldRejectBroaderRouteSwallowingExactOnlyAnchorNamespace() {
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(anchor("admin", "/admin", Require.BEARER));
            EndpointConfig admin = anchoredEndpoint("admin-ep", "ADMIN", "admin", null,
                    exactRoute("admin-entry", "/admin", "admin"));
            EndpointConfig catchAll = anchoredEndpoint("catch-all-ep", "CATCH", null,
                    auth(Require.NONE),
                    anchoredRoute("catch-all", "/", null));

            List<ConfigError> errors = validator.validate(gateway, List.of(admin, catchAll),
                    topologyWith("ADMIN", "CATCH"));

            // /admin/ and /admin/x match no route belonging to the anchor and would be served by the
            // unanchored catch-all with require: none — the anchor's bearer floor escaped.
            assertHasError(errors, "/endpoint/routes",
                    "route 'catch-all' path_prefix '/' contains anchor 'admin' namespace '/admin' without declaring it");
        }

        @Test
        @DisplayName("Rule 4b: Should accept a broader route when a prefix route under the anchor covers the namespace")
        void shouldAcceptBroaderRouteWhenAnchoredPrefixRouteCoversNamespace() {
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(anchor("admin", "/admin", Require.BEARER));
            EndpointConfig admin = anchoredEndpoint("admin-ep", "ADMIN", "admin", null,
                    anchoredRoute("admin-app", "/admin", "admin"));
            EndpointConfig catchAll = anchoredEndpoint("catch-all-ep", "CATCH", null,
                    auth(Require.NONE),
                    anchoredRoute("catch-all", "/", null));

            List<ConfigError> errors = validator.validate(gateway, List.of(admin, catchAll),
                    topologyWith("ADMIN", "CATCH"));

            // Longest-prefix selection gives every address under /admin to the anchored route, so the
            // catch-all alongside it is the ordinary topology and not a bypass.
            assertTrue(errors.isEmpty(),
                    () -> "a covered anchor namespace must not refuse a broader sibling route, got: " + errors);
        }

        /**
         * The covering route from the accepted case above, narrowed on one dimension at a time. Each
         * value is the {@code match} of a route declared at the anchor's own prefix — so it passes the
         * prefix-equality half of the coverage test and is rejected solely by the narrowing.
         */
        static Stream<Arguments> narrowedCoveringMatchers() {
            return Stream.of(
                    Arguments.of("methods", MatchConfig.builder().pathPrefix("/admin")
                            .methods(List.of(HttpMethod.GET)).build()),
                    Arguments.of("host", MatchConfig.builder().pathPrefix("/admin")
                            .host("admin.example.org").build()),
                    Arguments.of("headers", MatchConfig.builder().pathPrefix("/admin")
                            .headers(List.of(new MatchConfig.HeaderMatcher("X-Admin", null, "yes"))).build()));
        }

        @ParameterizedTest(name = "narrowed on {0}")
        @MethodSource("narrowedCoveringMatchers")
        @DisplayName("Rule 4b: Should reject when the covering route narrows beyond the path, so it serves only part of the namespace")
        void shouldRejectWhenCoveringRouteNarrowsBeyondThePath(String dimension, MatchConfig narrowed) {
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(anchor("admin", "/admin", Require.BEARER));
            EndpointConfig admin = anchoredEndpoint("admin-ep", "ADMIN", "admin", null,
                    RouteConfig.builder().id("admin-app").anchor("admin").match(narrowed).build());
            EndpointConfig catchAll = anchoredEndpoint("catch-all-ep", "CATCH", null,
                    auth(Require.NONE),
                    anchoredRoute("catch-all", "/", null));

            List<ConfigError> errors = validator.validate(gateway, List.of(admin, catchAll),
                    topologyWith("ADMIN", "CATCH"));

            // The member names the whole namespace but answers only the part its narrowing admits: a
            // POST (or a request from another host, or one without the header) matches no anchored
            // route and is served by the unanchored catch-all under require: none. Naming a namespace
            // is not covering it, so this must refuse exactly as an uncovered namespace does.
            assertHasError(errors, "/endpoint/routes",
                    "contains anchor 'admin' namespace '/admin' without declaring it");
        }

        @Test
        @DisplayName("Rule 4b: Should reject when the anchored prefix route covers only part of the namespace")
        void shouldRejectWhenAnchoredPrefixRouteCoversOnlyASubPath() {
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(anchor("admin", "/admin", Require.BEARER));
            EndpointConfig admin = anchoredEndpoint("admin-ep", "ADMIN", "admin", null,
                    anchoredRoute("admin-users", "/admin/users", "admin"));
            EndpointConfig catchAll = anchoredEndpoint("catch-all-ep", "CATCH", null,
                    auth(Require.NONE),
                    anchoredRoute("catch-all", "/", null));

            List<ConfigError> errors = validator.validate(gateway, List.of(admin, catchAll),
                    topologyWith("ADMIN", "CATCH"));

            // /admin/reports is inside the namespace but below no anchored route, so it still falls
            // through: partial coverage is not coverage.
            assertHasError(errors, "/endpoint/routes",
                    "contains anchor 'admin' namespace '/admin' without declaring it");
        }

        @Test
        @DisplayName("Rule 4b: Should not judge an exact route, which serves one address and never a namespace")
        void shouldNotJudgeExactRouteAgainstTheCoverageRule() {
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(anchor("admin", "/admin", Require.BEARER));
            EndpointConfig admin = anchoredEndpoint("admin-ep", "ADMIN", "admin", null,
                    anchoredRoute("admin-app", "/admin", "admin"));
            EndpointConfig root = anchoredEndpoint("root-ep", "ROOT", null,
                    auth(Require.NONE),
                    exactRoute("root-entry", "/", null));

            List<ConfigError> errors = validator.validate(gateway, List.of(admin, root),
                    topologyWith("ADMIN", "ROOT"));

            assertTrue(errors.isEmpty(),
                    () -> "an exact route matches one address and can swallow no namespace, got: " + errors);
        }

        @Test
        @DisplayName("Rule 5: Should reject an effective 'none' auth that weakens a non-none anchor floor")
        void shouldRejectWeakenedAuthFloor() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("api", anchor("api", "/api", Require.BEARER)));
            RouteConfig weakening = RouteConfig.builder().id("r").anchor("api")
                    .match(match("/api/x", HttpMethod.GET))
                    .auth(auth(Require.NONE)).build();
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "api", null, weakening);

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/endpoint/routes", "weakens the anchor 'api' floor 'bearer'");
        }

        @Test
        @DisplayName("Should accept a well-formed anchored configuration")
        void shouldAcceptValidAnchoredConfig() {
            GatewayConfig gateway = validGateway()
                    .anchors(Map.of("api", anchor("api", "/api", Require.BEARER)))
                    .tokenValidation(new TokenValidationConfig(List.of(
                            IssuerConfig.builder().name("main").issuer("https://idp.example").build())))
                    .build();
            EndpointConfig endpoint = anchoredEndpoint("api-ep", "API", "api", null,
                    anchoredRoute("r", "/api/orders", "api", HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("API"));

            assertTrue(errors.isEmpty(), () -> "a valid anchored config should have no violations, got: " + errors);
        }

        @Test
        @DisplayName("Should report multiple anchor violations together in one pass")
        void shouldAggregateAnchorViolations() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "api", anchor("api", "/api", null),
                    "apiv1", anchor("apiv1", "/api/v1", null)));
            EndpointConfig squatter = anchoredEndpoint("s", "S", null,
                    auth(Require.NONE),
                    anchoredRoute("sr", "/api/secret", null, HttpMethod.GET));

            List<ConfigError> errors = validator.validate(gateway, List.of(squatter), topologyWith("S"));

            assertAll("both the disjointness and squatter violations surface together",
                    () -> assertTrue(errors.size() >= 2, () -> "expected at least two violations, got: " + errors),
                    () -> assertHasError(errors, "/anchors", "pairwise disjoint"),
                    () -> assertHasError(errors, "/endpoint/routes", "does not declare it"));
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
        @DisplayName("A nested anchor prefix is never disjoint from its parent for any generated segment")
        void shouldRejectNestedAnchorPrefixesForAnyGeneratedSegment(String segment) {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "outer", anchor("outer", "/" + segment, null),
                    "inner", anchor("inner", "/" + segment + "/sub", null)));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/anchors", "pairwise disjoint");
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 8, count = 5)
        @DisplayName("Sibling anchor prefixes sharing a leading substring stay disjoint for any generated segment")
        void shouldAcceptDisjointSiblingAnchorsForAnyGeneratedSegment(String segment) {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "alpha", anchor("alpha", "/" + segment + "-a", null),
                    "beta", anchor("beta", "/" + segment + "-b", null)));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.isEmpty(),
                    () -> "sibling anchors sharing only a leading substring must stay disjoint, got: " + errors);
        }
    }

    @Nested
    @DisplayName("gRPC anchor-namespace containment exemption — rules 3 and 4 only (ADR-0007)")
    class GrpcNamespaceExemption {

        // The exemption is SCOPED, and this class pins both sides of that scope. Rules 3 and 4 judge a
        // route by where its OWN match key sits, which a service-rooted gRPC method path can never
        // satisfy — those two are waived (the first three cases below, the negative controls for the
        // inversion). Rule 4b judges the opposite geometry — what the route's prefix CONTAINS — and
        // selection is protocol-blind (RouteTable.lookup filters on path alone), so a gRPC route
        // swallowing an uncovered anchor namespace fails the boot exactly like an http one.

        private static final String ECHO_PATH = "/de.cuioss.sheriff.api.integration.grpc.Echo";
        private static final String SECURE_ECHO_PATH = "/de.cuioss.sheriff.api.integration.grpc.SecureEcho";

        private static RouteConfig grpcRoute(String id, String prefix, String anchorName, @Nullable AuthConfig auth) {
            return RouteConfig.builder()
                    .id(id)
                    .protocol(Protocol.GRPC)
                    .anchor(anchorName)
                    .match(match(prefix, HttpMethod.POST))
                    .auth(auth)
                    .build();
        }

        @Test
        @DisplayName("Rule 3 exemption: a gRPC route on a bare service path outside its declared anchor is accepted")
        void shouldExemptGrpcRouteFromDeclaredAnchorContainment() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("grpc", anchor("grpc", "/grpc", null)));
            EndpointConfig endpoint = anchoredEndpoint("echo", "ECHO", "grpc",
                    auth(Require.NONE),
                    grpcRoute("grpc-echo", ECHO_PATH, "grpc", null));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ECHO"));

            assertTrue(errors.isEmpty(),
                    () -> "a gRPC route on a service-rooted path outside its anchor namespace must not be rejected, got: "
                            + errors);
        }

        @Test
        @DisplayName("Rule 3/4 exemption: two gRPC routes under one anchor on bare service paths boot cleanly (native IT scenario)")
        void shouldAcceptTwoGrpcRoutesUnderOneAnchorOnBareServicePaths() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("grpc", anchor("grpc", "/grpc", null)));
            EndpointConfig endpoint = anchoredEndpoint("echo", "ECHO", "grpc",
                    auth(Require.NONE),
                    grpcRoute("grpc-echo", ECHO_PATH, "grpc", null),
                    grpcRoute("grpc-bearer", SECURE_ECHO_PATH, "grpc", null));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ECHO"));

            assertTrue(errors.isEmpty(),
                    () -> "the two gRPC routes that failed the native IT boot must now validate cleanly, got: " + errors);
        }

        @Test
        @DisplayName("Rule 4 exemption: a gRPC route that would squat inside a catch-all anchor namespace is accepted")
        void shouldExemptGrpcRouteFromUndeclaredSquatterRule() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("root", anchor("root", "/", null)));
            EndpointConfig endpoint = anchoredEndpoint("echo", "ECHO", null,
                    auth(Require.NONE),
                    grpcRoute("grpc-echo", ECHO_PATH, null, null));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ECHO"));

            assertTrue(errors.isEmpty(),
                    () -> "a gRPC route inside a catch-all anchor namespace must not be rejected as a squatter, got: "
                            + errors);
        }

        @Test
        @DisplayName("Rule 4b applies to gRPC: a gRPC route whose prefix swallows an uncovered anchor namespace fails the boot")
        void shouldRejectGrpcRouteContainingUncoveredAnchorNamespace() {
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(anchor("admin", "/admin", Require.BEARER));
            EndpointConfig admin = anchoredEndpoint("admin-ep", "ADMIN", "admin", null,
                    exactRoute("admin-entry", "/admin", "admin"));
            EndpointConfig echo = anchoredEndpoint("echo", "ECHO", null,
                    auth(Require.NONE),
                    grpcRoute("grpc-echo", "/", null, null));

            List<ConfigError> errors = validator.validate(gateway, List.of(admin, echo),
                    topologyWith("ADMIN", "ECHO"));

            // Selection is protocol-blind, so a plain GET /admin/x matches no route belonging to the
            // anchor, falls through to this gRPC route, and is served with its require: none posture
            // and its security_headers block — the anchor's bearer floor escaped for every address in
            // the namespace but the one exact string.
            assertHasError(errors, "/endpoint/routes",
                    "route 'grpc-echo' path_prefix '/' contains anchor 'admin' namespace '/admin' without declaring it");
        }

        @Test
        @DisplayName("Rule 4b coverage exception applies to gRPC too: a covered anchor namespace boots beside a gRPC catch-all")
        void shouldAcceptGrpcRouteContainingCoveredAnchorNamespace() {
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(anchor("admin", "/admin", Require.BEARER));
            EndpointConfig admin = anchoredEndpoint("admin-ep", "ADMIN", "admin", null,
                    anchoredRoute("admin-app", "/admin", "admin"));
            EndpointConfig echo = anchoredEndpoint("echo", "ECHO", null,
                    auth(Require.NONE),
                    grpcRoute("grpc-echo", "/", null, null));

            List<ConfigError> errors = validator.validate(gateway, List.of(admin, echo),
                    topologyWith("ADMIN", "ECHO"));

            // Same geometry as the case above with the one thing that matters changed: the anchor now
            // carries a prefix route at its own path_prefix, so longest-prefix selection gives it every
            // address in the namespace and the gRPC route alongside it is not a bypass.
            assertTrue(errors.isEmpty(),
                    () -> "a covered anchor namespace must not refuse a broader gRPC sibling route, got: " + errors);
        }

        @Test
        @DisplayName("Rule 4b applies to gRPC only as a container: a gRPC route on a bare service path swallows no anchor")
        void shouldNotJudgeBareServicePathGrpcRouteAgainstTheCoverageRule() {
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(anchor("admin", "/admin", Require.BEARER));
            EndpointConfig admin = anchoredEndpoint("admin-ep", "ADMIN", "admin", null,
                    exactRoute("admin-entry", "/admin", "admin"));
            EndpointConfig echo = anchoredEndpoint("echo", "ECHO", null,
                    auth(Require.NONE),
                    grpcRoute("grpc-echo", ECHO_PATH, null, null));

            List<ConfigError> errors = validator.validate(gateway, List.of(admin, echo),
                    topologyWith("ADMIN", "ECHO"));

            // The shipped gRPC descriptors ride bare service paths, which contain no anchor prefix, so
            // extending rule 4b to gRPC leaves them booting exactly as before.
            assertTrue(errors.isEmpty(),
                    () -> "a service-rooted gRPC route contains no anchor namespace and must not be refused, got: "
                            + errors);
        }

        @Test
        @DisplayName("Containment stays enforced for a websocket route outside its declared anchor namespace")
        void shouldStillEnforceContainmentForNonGrpcRoute() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("api", anchor("api", "/api", null)));
            RouteConfig websocket = RouteConfig.builder().id("ws").anchor("api")
                    .protocol(Protocol.WEBSOCKET)
                    .match(match("/billing", HttpMethod.GET))
                    .auth(auth(Require.NONE)).build();
            EndpointConfig endpoint = anchoredEndpoint("orders", "ORDERS", "api", null, websocket);

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ORDERS"));

            assertHasError(errors, "/endpoint/routes", "is not inside its declared anchor 'api'");
        }

        @Test
        @DisplayName("Auth floor stays enforced for a gRPC route: effective 'none' still weakens a non-none anchor floor")
        void shouldStillEnforceAuthFloorForGrpcRoute() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of("grpc", anchor("grpc", "/grpc", Require.BEARER)));
            EndpointConfig endpoint = anchoredEndpoint("echo", "ECHO", "grpc", null,
                    grpcRoute("grpc-echo", ECHO_PATH, "grpc", auth(Require.NONE)));

            List<ConfigError> errors = validator.validate(gateway, List.of(endpoint), topologyWith("ECHO"));

            assertHasError(errors, "/endpoint/routes", "weakens the anchor 'grpc' floor 'bearer'");
        }
    }

    @Nested
    @DisplayName("The fail-closed access→auth matrix (ADR-0013)")
    class AccessAuthMatrix {

        @Test
        @DisplayName("Rule bff→authenticated: Should reject a type 'bff' anchor that is not access: authenticated")
        void shouldRejectBffAnchorThatIsNotAuthenticated() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "portal", matrixAnchor("portal", "/portal", AnchorType.BFF, AccessLevel.PUBLIC, null)));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/anchors/portal", "is type 'bff' and must declare access: authenticated");
        }

        @ParameterizedTest
        @EnumSource(value = AnchorType.class, names = {"PROXY", "ASSET"})
        @DisplayName("Rule public+auth: Should reject an access: public anchor declaring an auth block for any non-bff type")
        void shouldRejectPublicAnchorDeclaringAuthBlock(AnchorType type) {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "open", matrixAnchor("open", "/open", type, AccessLevel.PUBLIC, Require.BEARER)));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/anchors/open", "is access: public and must not declare an auth block");
        }

        @ParameterizedTest
        @EnumSource(Require.class)
        @DisplayName("Rule public+auth: Should reject an access: public anchor for every auth-floor value in the vocabulary")
        void shouldRejectPublicAnchorForEveryAuthFloorValue(Require require) {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "open", matrixAnchor("open", "/open", AnchorType.PROXY, AccessLevel.PUBLIC, require)));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/anchors/open", "is access: public and must not declare an auth block");
        }

        static Stream<Arguments> authenticatedAnchorsWithoutBackedFloor() {
            return Stream.of(
                    Arguments.of("no auth floor at all", null, "declares no non-'none' auth floor"),
                    Arguments.of("an explicit 'none' floor", Require.NONE, "declares no non-'none' auth floor"),
                    Arguments.of("a bearer floor with no token_validation issuer", Require.BEARER,
                            "access: authenticated bearer floor requires token_validation with at least one issuer"),
                    Arguments.of("a session floor with no oidc block", Require.SESSION,
                            "access: authenticated session floor requires an oidc block"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("authenticatedAnchorsWithoutBackedFloor")
        @DisplayName("Rules authenticated→floor and authenticated backing: Should reject an access: authenticated anchor without a backed auth floor")
        void shouldRejectAuthenticatedAnchorWithoutBackedFloor(String label, @Nullable Require require,
                String expectedDetail) {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "secure", matrixAnchor("secure", "/secure", AnchorType.PROXY, AccessLevel.AUTHENTICATED, require)));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertHasError(errors, "/anchors/secure", expectedDetail);
        }

        @Test
        @DisplayName("Should accept a type 'bff' anchor that is access: authenticated with a backed bearer floor")
        void shouldAcceptAuthenticatedBffWithBackedBearerFloor() {
            GatewayConfig gateway = gatewayWithAnchorAndIssuer(
                    matrixAnchor("portal", "/portal", AnchorType.BFF, AccessLevel.AUTHENTICATED, Require.BEARER));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.isEmpty(),
                    () -> "a bff+authenticated anchor with a backed bearer floor must satisfy the matrix, got: " + errors);
        }

        @ParameterizedTest
        @EnumSource(value = AnchorType.class, names = {"PROXY", "ASSET"})
        @DisplayName("Should accept an access: public anchor with no auth block for any non-bff type")
        void shouldAcceptPublicAnchorWithoutAuthBlock(AnchorType type) {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "open", matrixAnchor("open", "/open", type, AccessLevel.PUBLIC, null)));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertTrue(errors.isEmpty(),
                    () -> "a public anchor declaring no auth block must satisfy the matrix, got: " + errors);
        }

        @Test
        @DisplayName("Should report every matrix violation together in one pass")
        void shouldAggregateMatrixViolationsInOnePass() {
            GatewayConfig gateway = gatewayWithAnchors(Map.of(
                    "portal", matrixAnchor("portal", "/portal", AnchorType.BFF, AccessLevel.PUBLIC, null),
                    "open", matrixAnchor("open", "/open", AnchorType.PROXY, AccessLevel.PUBLIC, Require.BEARER),
                    "secure", matrixAnchor("secure", "/secure", AnchorType.PROXY, AccessLevel.AUTHENTICATED, null)));

            List<ConfigError> errors = validator.validate(gateway, List.of(), topologyWith());

            assertAll("all three matrix violations surface together",
                    () -> assertTrue(errors.size() >= 3, () -> "expected at least three violations, got: " + errors),
                    () -> assertHasError(errors, "/anchors/portal",
                            "is type 'bff' and must declare access: authenticated"),
                    () -> assertHasError(errors, "/anchors/open",
                            "is access: public and must not declare an auth block"),
                    () -> assertHasError(errors, "/anchors/secure", "declares no non-'none' auth floor"));
        }
    }
}
