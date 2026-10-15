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
package de.cuioss.sheriff.gateway.routing;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.sheriff.gateway.asset.AssetSource;
import de.cuioss.sheriff.gateway.asset.DirectoryAssetSource;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.RedirectConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedAsset;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@EnableGeneratorController
@DisplayName("Route — the one per-route record")
class RouteTest {

    @Nested
    @DisplayName("Defaults")
    class Defaults {

        @Test
        @DisplayName("Should default an absent protocol to HTTP")
        void shouldDefaultProtocolToHttp() {
            Route route = proxyRoute().protocol(null).build();

            assertEquals(Protocol.HTTP, route.protocol(), "An absent protocol resolves to HTTP");
        }

        @Test
        @DisplayName("Should keep a declared protocol unchanged")
        void shouldKeepDeclaredProtocol() {
            Route route = proxyRoute().protocol(Protocol.GRPC).build();

            assertEquals(Protocol.GRPC, route.protocol(), "A declared protocol is not overridden by the default");
        }

        @Test
        @DisplayName("Should compile an absent matcher from the match block")
        void shouldCompileAbsentMatcherFromMatch() {
            String exactPath = "/" + Generators.letterStrings(3, 12).next();

            Route route = proxyRoute().match(MatchConfig.builder().path(exactPath).build()).build();

            assertAll("compiled matcher",
                    () -> assertEquals(exactPath, route.matcher().matchKey(),
                            "The compiled matcher carries the match block's key"),
                    () -> assertTrue(route.matcher().isExact(),
                            "The compiled matcher keeps the match block's exactness"),
                    () -> assertTrue(route.matcher().matchesPath(exactPath),
                            "The compiled matcher matches the declared exact path"));
        }

        @Test
        @DisplayName("Should keep an explicitly supplied matcher instead of recompiling it")
        void shouldKeepSuppliedMatcher() {
            RouteMatcher supplied = RouteMatcher.from(MatchConfig.builder().pathPrefix("/supplied").build());

            Route route = proxyRoute().matcher(supplied).build();

            assertSame(supplied, route.matcher(), "A supplied matcher is held as given");
        }

        @Test
        @DisplayName("Should default an absent effectiveForward to the all-absent forward-all block")
        void shouldDefaultForwardToForwardAll() {
            Route route = proxyRoute().effectiveForward(null).build();

            assertEquals(ForwardConfig.builder().build(), route.effectiveForward(),
                    "An absent forward block resolves to the all-absent forward-all posture");
        }

        @Test
        @DisplayName("Should default an absent securityProfile to the fail-closed STRICT")
        void shouldDefaultSecurityProfileToStrict() {
            Route omitted = proxyRoute().build();
            Route explicitNull = proxyRoute().securityProfile(null).build();

            assertAll("fail-closed profile default",
                    () -> assertEquals(SecurityProfile.STRICT, omitted.securityProfile(),
                            "An omitted profile resolves to the most restrictive mode"),
                    () -> assertEquals(SecurityProfile.STRICT, explicitNull.securityProfile(),
                            "An explicitly absent profile resolves to the most restrictive mode"),
                    () -> assertTrue(omitted.securityProfile().skippableValidationEnabled(),
                            "The default keeps the skippable validation half switched on"));
        }

        @Test
        @DisplayName("Should normalize every absent collection to empty")
        void shouldNormalizeAbsentCollectionsToEmpty() {
            Route route = proxyRoute()
                    .effectiveAllowedMethods(null)
                    .neededScopes(null)
                    .effectiveAllowedPaths(null)
                    .effectiveAllowedOrigins(null)
                    .build();

            assertAll("absent collections",
                    () -> assertTrue(route.effectiveAllowedMethods().isEmpty(), "No verb is allowed by default"),
                    () -> assertTrue(route.neededScopes().isEmpty(), "No scope is needed by default"),
                    () -> assertTrue(route.effectiveAllowedPaths().isEmpty(), "No path whitelist by default"),
                    () -> assertTrue(route.effectiveAllowedOrigins().isEmpty(), "No origin allowlist by default"));
        }

        @Test
        @DisplayName("Should leave the boot-attached and optional components absent on a pre-boot route")
        void shouldLeaveBootAttachedComponentsAbsent() {
            Route route = proxyRoute().build();

            assertAll("pre-boot route",
                    () -> assertNull(route.protocolProcessor(), "No processor before the boot pass"),
                    () -> assertNull(route.securityConfiguration(),
                            "No configuration before the boot pass, the fail-closed absent value"),
                    () -> assertNull(route.assetSource(), "No asset source before the boot pass"),
                    () -> assertNull(route.anchor(), "An unanchored route carries no anchor name"),
                    () -> assertNull(route.locationRewriter(), "No rewriter unless rewrite_location is on"),
                    () -> assertNull(route.effectiveWebSocketIdleTimeoutSeconds(),
                            "No idle timeout for a non-WebSocket route"));
        }

        @Test
        @DisplayName("Should expose the match block's key as the route's match key")
        void shouldExposeMatchKey() {
            String prefix = "/" + Generators.letterStrings(3, 12).next();

            Route route = proxyRoute().match(MatchConfig.builder().pathPrefix(prefix).build()).build();

            assertEquals(prefix, route.matchKey(), "The match key is the declared path prefix");
        }
    }

    @Nested
    @DisplayName("Mandatory components")
    class MandatoryComponents {

        @Test
        @DisplayName("Should refuse a route without an id")
        void shouldRefuseMissingId() {
            var builder = proxyRoute().id(null);

            var thrown = assertThrows(NullPointerException.class, builder::build, "The id is mandatory");
            assertEquals("id", thrown.getMessage(), "The refusal names the missing component");
        }

        @Test
        @DisplayName("Should refuse a route without a match block")
        void shouldRefuseMissingMatch() {
            var builder = proxyRoute().match(null);

            var thrown = assertThrows(NullPointerException.class, builder::build, "The match block is mandatory");
            assertEquals("match", thrown.getMessage(), "The refusal names the missing component");
        }

        @Test
        @DisplayName("Should refuse a route without an effective auth posture")
        void shouldRefuseMissingEffectiveAuth() {
            var builder = proxyRoute().effectiveAuth(null);

            var thrown = assertThrows(NullPointerException.class, builder::build,
                    "The effective auth posture is mandatory");
            assertEquals("effectiveAuth", thrown.getMessage(), "The refusal names the missing component");
        }
    }

    @Nested
    @DisplayName("Terminal-action invariant")
    class TerminalAction {

        @Test
        @DisplayName("Should accept each single terminal action")
        void shouldAcceptEachSingleTerminalAction() {
            Route proxy = proxyRoute().build();
            Route asset = assetRoute().build();
            Route redirect = baseRoute().redirect(redirect()).build();

            assertAll("exactly one terminal action",
                    () -> assertEquals(upstream(), proxy.upstream(), "A proxy route carries its upstream"),
                    () -> assertEquals(asset(), asset.asset(), "An asset route carries its asset action"),
                    () -> assertEquals(redirect(), redirect.redirect(), "A redirect route carries its redirect"));
        }

        @Test
        @DisplayName("Should refuse a route with no terminal action")
        void shouldRefuseZeroTerminalActions() {
            String id = Generators.letterStrings(3, 12).next();
            var builder = baseRoute().id(id);

            var thrown = assertThrows(IllegalArgumentException.class, builder::build,
                    "A route resolving no terminal action is refused");
            assertTrue(thrown.getMessage().contains(id), "The refusal names the route");
        }

        @Test
        @DisplayName("Should refuse a route with two or three terminal actions")
        void shouldRefuseSeveralTerminalActions() {
            var upstreamAndAsset = baseRoute().upstream(upstream()).asset(asset());
            var upstreamAndRedirect = baseRoute().upstream(upstream()).redirect(redirect());
            var assetAndRedirect = baseRoute().asset(asset()).redirect(redirect());
            var allThree = baseRoute().upstream(upstream()).asset(asset()).redirect(redirect());

            assertAll("several terminal actions",
                    () -> assertThrows(IllegalArgumentException.class, upstreamAndAsset::build,
                            "upstream together with asset is refused"),
                    () -> assertThrows(IllegalArgumentException.class, upstreamAndRedirect::build,
                            "upstream together with redirect is refused"),
                    () -> assertThrows(IllegalArgumentException.class, assetAndRedirect::build,
                            "asset together with redirect is refused"),
                    () -> assertThrows(IllegalArgumentException.class, allThree::build,
                            "all three terminal actions together are refused"));
        }
    }

    @Nested
    @DisplayName("Defensive copies")
    class DefensiveCopies {

        @Test
        @DisplayName("Should not reflect later changes to the collections it was built from")
        void shouldNotReflectSourceMutation() {
            Set<HttpMethod> methods = new HashSet<>(Set.of(HttpMethod.GET));
            Set<String> scopes = new HashSet<>(Set.of("read"));
            List<String> paths = new ArrayList<>(List.of("/allowed"));
            Set<String> origins = new HashSet<>(Set.of("https://app.example"));
            Route route = proxyRoute()
                    .effectiveAllowedMethods(methods)
                    .neededScopes(scopes)
                    .effectiveAllowedPaths(paths)
                    .effectiveAllowedOrigins(origins)
                    .build();

            methods.add(HttpMethod.DELETE);
            scopes.add("admin");
            paths.add("/other");
            origins.add("https://evil.example");

            assertAll("detached copies",
                    () -> assertEquals(Set.of(HttpMethod.GET), route.effectiveAllowedMethods(),
                            "The verb set is detached from its source"),
                    () -> assertEquals(Set.of("read"), route.neededScopes(), "The scope set is detached"),
                    () -> assertEquals(List.of("/allowed"), route.effectiveAllowedPaths(),
                            "The path whitelist is detached"),
                    () -> assertEquals(Set.of("https://app.example"), route.effectiveAllowedOrigins(),
                            "The origin allowlist is detached"));
        }

        @Test
        @DisplayName("Should expose every collection as unmodifiable")
        void shouldExposeUnmodifiableCollections() {
            Route route = proxyRoute()
                    .effectiveAllowedMethods(Set.of(HttpMethod.GET))
                    .neededScopes(Set.of("read"))
                    .effectiveAllowedPaths(List.of("/allowed"))
                    .effectiveAllowedOrigins(Set.of("https://app.example"))
                    .build();
            Set<HttpMethod> methods = route.effectiveAllowedMethods();
            Set<String> scopes = route.neededScopes();
            List<String> paths = route.effectiveAllowedPaths();
            Set<String> origins = route.effectiveAllowedOrigins();

            assertAll("unmodifiable views",
                    () -> assertThrows(UnsupportedOperationException.class, () -> methods.add(HttpMethod.POST),
                            "The verb set cannot be widened through the accessor"),
                    () -> assertThrows(UnsupportedOperationException.class, () -> scopes.add("admin"),
                            "The scope set cannot be changed through the accessor"),
                    () -> assertThrows(UnsupportedOperationException.class, () -> paths.add("/other"),
                            "The path whitelist cannot be changed through the accessor"),
                    () -> assertThrows(UnsupportedOperationException.class,
                            () -> origins.add("https://evil.example"),
                            "The origin allowlist cannot be changed through the accessor"));
        }
    }

    @Nested
    @DisplayName("Two-pass construction through toBuilder()")
    class TwoPassConstruction {

        @Test
        @DisplayName("Should attach the boot-only components while leaving every resolved value unchanged")
        void shouldAttachBootComponentsWithoutChangingResolvedValues() {
            Route preBoot = fullyResolvedAssetRoute();
            ProtocolProcessor processor = new ProtocolProcessorRegistry().require(Protocol.HTTP, preBoot.id());
            SecurityConfiguration configuration = SecurityConfiguration.builder().build();
            AssetSource source = new DirectoryAssetSource(Path.of("/srv/assets"), AccessLevel.PUBLIC,
                    "index.html", "index.html", Map.of());

            Route assembled = preBoot.toBuilder()
                    .protocolProcessor(processor)
                    .securityProfile(SecurityProfile.LENIENT)
                    .securityConfiguration(configuration)
                    .assetSource(source)
                    .build();

            assertAll("boot-attached components",
                    () -> assertSame(processor, assembled.protocolProcessor(), "The processor is attached"),
                    () -> assertEquals(SecurityProfile.LENIENT, assembled.securityProfile(),
                            "The resolved profile replaces the fail-closed default"),
                    () -> assertSame(configuration, assembled.securityConfiguration(),
                            "The security configuration is attached"),
                    () -> assertSame(source, assembled.assetSource(), "The asset source is attached"));
            assertAll("resolved values unchanged",
                    () -> assertEquals(preBoot.id(), assembled.id(), "id"),
                    () -> assertEquals(preBoot.protocol(), assembled.protocol(), "protocol"),
                    () -> assertEquals(preBoot.anchor(), assembled.anchor(), "anchor"),
                    () -> assertEquals(preBoot.match(), assembled.match(), "match"),
                    () -> assertSame(preBoot.matcher(), assembled.matcher(), "matcher"),
                    () -> assertEquals(preBoot.effectiveAuth(), assembled.effectiveAuth(), "effectiveAuth"),
                    () -> assertEquals(preBoot.effectiveAllowedMethods(), assembled.effectiveAllowedMethods(),
                            "effectiveAllowedMethods"),
                    () -> assertEquals(preBoot.neededScopes(), assembled.neededScopes(), "neededScopes"),
                    () -> assertEquals(preBoot.effectiveSecurityFilter(), assembled.effectiveSecurityFilter(),
                            "effectiveSecurityFilter"),
                    () -> assertEquals(preBoot.effectiveAllowedPaths(), assembled.effectiveAllowedPaths(),
                            "effectiveAllowedPaths"),
                    () -> assertEquals(preBoot.securityHeaders(), assembled.securityHeaders(), "securityHeaders"),
                    () -> assertEquals(preBoot.effectiveForward(), assembled.effectiveForward(), "effectiveForward"),
                    () -> assertEquals(preBoot.retryEnabled(), assembled.retryEnabled(), "retryEnabled"),
                    () -> assertEquals(preBoot.notModifiedEnabled(), assembled.notModifiedEnabled(),
                            "notModifiedEnabled"),
                    () -> assertEquals(preBoot.asset(), assembled.asset(), "asset"),
                    () -> assertEquals(preBoot.effectiveAllowedOrigins(), assembled.effectiveAllowedOrigins(),
                            "effectiveAllowedOrigins"),
                    () -> assertEquals(preBoot.effectiveWebSocketIdleTimeoutSeconds(),
                            assembled.effectiveWebSocketIdleTimeoutSeconds(), "effectiveWebSocketIdleTimeoutSeconds"));
        }

        @Test
        @DisplayName("Should equal the pre-boot route again once the boot-only components are detached")
        void shouldRoundTripToPreBootRoute() {
            Route preBoot = fullyResolvedProxyRoute();
            Route assembled = preBoot.toBuilder()
                    .protocolProcessor(new ProtocolProcessorRegistry().require(Protocol.HTTP, preBoot.id()))
                    .securityProfile(SecurityProfile.MINIMAL)
                    .securityConfiguration(SecurityConfiguration.builder().build())
                    .build();

            Route detached = assembled.toBuilder()
                    .protocolProcessor(null)
                    .securityProfile(null)
                    .securityConfiguration(null)
                    .build();

            assertNotEquals(preBoot, assembled, "Attaching the boot-only components changes the route");
            assertEquals(preBoot, detached,
                    "Detaching them again restores the pre-boot route, so no resolved value was touched");
        }

        @Test
        @DisplayName("Should carry the pure derivations the pre-boot pass sets through the boot pass")
        void shouldCarryPreBootDerivationsThroughBootPass() {
            Route preBoot = fullyResolvedProxyRoute();

            Route assembled = preBoot.toBuilder()
                    .protocolProcessor(new ProtocolProcessorRegistry().require(Protocol.HTTP, preBoot.id()))
                    .build();

            assertAll("pre-boot derivations",
                    () -> assertSame(preBoot.locationRewriter(), assembled.locationRewriter(),
                            "The location rewriter survives the boot pass as the same instance"),
                    () -> assertFalse(assembled.effectiveAllowedPaths().isEmpty(),
                            "The allowed paths derived pre-boot survive the boot pass"));
        }
    }

    private static Route.RouteBuilder baseRoute() {
        return Route.builder()
                .id(Generators.letterStrings(3, 12).next())
                .match(MatchConfig.builder().pathPrefix("/api").build())
                .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                .effectiveAllowedMethods(Set.of(HttpMethod.GET));
    }

    private static Route.RouteBuilder proxyRoute() {
        return baseRoute().upstream(upstream());
    }

    private static Route.RouteBuilder assetRoute() {
        return baseRoute().asset(asset());
    }

    private static Route fullyResolvedProxyRoute() {
        MatchConfig match = MatchConfig.builder().pathPrefix("/api").build();
        return proxyRoute()
                .match(match)
                .anchor(Generators.letterStrings(3, 12).next())
                .effectiveAllowedMethods(Set.of(HttpMethod.GET, HttpMethod.POST))
                .neededScopes(Set.of("read"))
                .effectiveSecurityFilter(SecurityFilterConfig.builder().allowedPaths(List.of("/api/ok")).build())
                .effectiveAllowedPaths(List.of("/api/ok"))
                .securityHeaders(SecurityHeadersConfig.builder().build())
                .retryEnabled(true)
                .notModifiedEnabled(true)
                .locationRewriter(new LocationRewriter(upstream(), match.matchKey(), match.isExact()))
                .build();
    }

    private static Route fullyResolvedAssetRoute() {
        return assetRoute()
                .anchor(Generators.letterStrings(3, 12).next())
                .effectiveAllowedMethods(Set.of(HttpMethod.GET))
                .neededScopes(Set.of("read"))
                .effectiveSecurityFilter(SecurityFilterConfig.builder().allowedPaths(List.of("/api/ok")).build())
                .effectiveAllowedPaths(List.of("/api/ok"))
                .securityHeaders(SecurityHeadersConfig.builder().build())
                .notModifiedEnabled(true)
                .build();
    }

    private static ResolvedUpstream upstream() {
        return new ResolvedUpstream("https", "a.example", 443, "/svc");
    }

    private static ResolvedAsset asset() {
        return ResolvedAsset.directory("/srv/assets", AccessLevel.PUBLIC, "index.html", "index.html");
    }

    private static RedirectConfig redirect() {
        return new RedirectConfig("/new-home", 301, false, false);
    }
}
