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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
import de.cuioss.sheriff.gateway.config.model.MatchConfig.HeaderMatcher;
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
import de.cuioss.test.generator.junit.parameterized.GeneratorType;
import de.cuioss.test.generator.junit.parameterized.GeneratorsSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

/**
 * Contract test for {@link Route}, the one per-route record, and for the routing collaborators it is
 * compiled with: the {@link RouteMatcher} compiled from its {@code match} block and the
 * {@link ProtocolProcessorRegistry} the boot pass selects its processor from.
 */
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
        @DisplayName("Should keep a supplied matcher compiled from the route's own match block")
        void shouldKeepMatcherCompiledFromOwnMatch() {
            MatchConfig match = MatchConfig.builder().pathPrefix("/" + Generators.letterStrings(3, 12).next()).build();
            RouteMatcher supplied = RouteMatcher.from(match);

            Route route = proxyRoute().match(match).matcher(supplied).build();

            assertSame(supplied, route.matcher(), "A matcher compiled from this very block is held as given");
        }

        @Test
        @DisplayName("Should recompile a supplied matcher that was compiled from a different match block")
        void shouldRecompileMatcherCompiledFromOtherMatch() {
            String routePrefix = "/" + Generators.letterStrings(3, 12).next();
            RouteMatcher stale = RouteMatcher.from(MatchConfig.builder().pathPrefix(routePrefix + "-stale").build());

            Route route = proxyRoute().match(MatchConfig.builder().pathPrefix(routePrefix).build())
                    .matcher(stale).build();

            assertAll("recompiled matcher",
                    () -> assertNotSame(stale, route.matcher(), "A matcher of another block is not kept"),
                    () -> assertEquals(routePrefix, route.matcher().matchKey(),
                            "The matcher is compiled from the route's own match block"));
        }

        @Test
        @DisplayName("Should recompile the matcher when a toBuilder() copy changes the match block")
        void shouldRecompileMatcherWhenCopyChangesMatch() {
            String changedPrefix = "/" + Generators.letterStrings(3, 12).next() + "/changed";
            Route original = proxyRoute().build();

            Route changed = original.toBuilder().match(MatchConfig.builder().pathPrefix(changedPrefix).build()).build();

            assertAll("matcher follows match",
                    () -> assertEquals(changedPrefix, changed.matcher().matchKey(),
                            "The copy selects on its new match block, not the carried-over matcher"),
                    () -> assertTrue(changed.matcher().matchesPath(changedPrefix + "/x"),
                            "The recompiled matcher matches below the new prefix"),
                    () -> assertFalse(changed.matcher().matchesPath(original.matchKey()),
                            "The recompiled matcher no longer matches the old prefix"));
        }

        @Test
        @DisplayName("Should derive the allowed-paths whitelist from the effective security filter")
        void shouldDeriveAllowedPathsFromSecurityFilter() {
            String allowed = "/api/" + Generators.letterStrings(3, 12).next();
            SecurityFilterConfig filter = SecurityFilterConfig.builder().allowedPaths(List.of(allowed)).build();

            Route route = proxyRoute().effectiveSecurityFilter(filter).build();

            assertEquals(List.of(allowed), route.effectiveAllowedPaths(),
                    "A whitelist the filter declares is the route's whitelist");
        }

        @Test
        @DisplayName("Should re-derive the allowed-paths whitelist when a toBuilder() copy changes the filter")
        void shouldRederiveAllowedPathsWhenCopyChangesFilter() {
            String changed = "/api/" + Generators.letterStrings(3, 12).next();
            Route original = proxyRoute()
                    .effectiveSecurityFilter(SecurityFilterConfig.builder().allowedPaths(List.of("/api/old")).build())
                    .build();

            Route copy = original.toBuilder()
                    .effectiveSecurityFilter(SecurityFilterConfig.builder().allowedPaths(List.of(changed)).build())
                    .build();
            Route withoutFilter = original.toBuilder().effectiveSecurityFilter(null).build();

            assertAll("allowed paths follow the filter",
                    () -> assertEquals(List.of(changed), copy.effectiveAllowedPaths(),
                            "The copy enforces its new filter's whitelist, not the old one"),
                    () -> assertTrue(withoutFilter.effectiveAllowedPaths().isEmpty(),
                            "Dropping the filter drops its whitelist"));
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
        @DisplayName("Should carry an explicitly resolved securityProfile unchanged")
        void shouldCarryExplicitSecurityProfile() {
            Route lenient = proxyRoute().securityProfile(SecurityProfile.LENIENT).build();
            Route minimal = proxyRoute().securityProfile(SecurityProfile.MINIMAL).build();

            assertAll("explicit profile",
                    () -> assertEquals(SecurityProfile.LENIENT, lenient.securityProfile(),
                            "An explicitly resolved LENIENT overrides the fail-closed default"),
                    () -> assertEquals(SecurityProfile.MINIMAL, minimal.securityProfile(),
                            "An explicitly resolved MINIMAL overrides the fail-closed default"),
                    () -> assertFalse(minimal.securityProfile().skippableValidationEnabled(),
                            "The MINIMAL route reports its skippable half as disabled"));
        }

        @Test
        @DisplayName("Should resolve an absent forward block to forward-all, keeping every list absent")
        void shouldKeepForwardAllListsAbsent() {
            Route route = proxyRoute().effectiveForward(null).build();

            assertAll("forward-all posture",
                    () -> assertNull(route.effectiveForward().headersAllow(),
                            "Every list stays absent, never an empty allowlist that would forward nothing"),
                    () -> assertNull(route.effectiveForward().headersDeny(), "No header denylist"),
                    () -> assertNull(route.effectiveForward().queryAllow(), "No query allowlist"),
                    () -> assertNull(route.effectiveForward().queryDeny(), "No query denylist"),
                    () -> assertTrue(route.effectiveForward().setHeaders().isEmpty(),
                            "set_headers carries no mode semantics and keeps the absent-means-empty normalization"));
        }

        @Test
        @DisplayName("Should normalize every absent collection to empty")
        void shouldNormalizeAbsentCollectionsToEmpty() {
            Route route = proxyRoute()
                    .effectiveAllowedMethods(null)
                    .neededScopes(null)
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
                    () -> assertNull(route.effectiveSecurityFilter(), "No security filter unless one resolves"),
                    () -> assertNull(route.securityHeaders(), "No response-header posture unless one resolves"),
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

        @Test
        @DisplayName("Should expose an exact route's declared path as its match key")
        void shouldExposeExactMatchKey() {
            String exactPath = "/" + Generators.letterStrings(3, 12).next() + "/1";

            Route route = proxyRoute().match(MatchConfig.builder().path(exactPath).build()).build();

            assertEquals(exactPath, route.matchKey(), "The match key is the declared exact path");
        }
    }

    @Nested
    @DisplayName("Resolved values")
    class ResolvedValues {

        @Test
        @DisplayName("Should carry the materialized WebSocket settings")
        void shouldCarryMaterializedWebSocketSettings() {
            Route route = proxyRoute()
                    .protocol(Protocol.WEBSOCKET)
                    .effectiveAllowedOrigins(Set.of("https://app.example.com"))
                    .effectiveWebSocketIdleTimeoutSeconds(300)
                    .build();

            assertAll("WebSocket settings",
                    () -> assertEquals(Set.of("https://app.example.com"), route.effectiveAllowedOrigins(),
                            "The origin allowlist is carried"),
                    () -> assertEquals(300, route.effectiveWebSocketIdleTimeoutSeconds(),
                            "The idle timeout is carried"));
        }

        @Test
        @DisplayName("Should carry the materialized effective posture")
        void shouldCarryMaterializedEffectivePosture() {
            Route route = fullyResolvedProxyRoute();

            assertAll("effective posture",
                    () -> assertNotNull(route.anchor(), "The resolving anchor name is carried"),
                    () -> assertEquals(Require.NONE, route.effectiveAuth().require(), "The auth posture is carried"),
                    () -> assertEquals(Set.of(HttpMethod.GET, HttpMethod.POST), route.effectiveAllowedMethods(),
                            "The verb allowlist is carried"),
                    () -> assertNotNull(route.effectiveSecurityFilter(), "The security filter is carried"),
                    () -> assertNotNull(route.securityHeaders(), "The response-header posture is carried"),
                    () -> assertEquals(List.of("Accept"), route.effectiveForward().headersAllow(),
                            "The materialized forward allowlist is carried"),
                    () -> assertNotNull(route.locationRewriter(),
                            "The rewrite_location mapping is carried when the route opts in"));
        }
    }

    @Nested
    @DisplayName("equals / hashCode / toString contract")
    class ValueObjectContract {

        @Test
        @DisplayName("Should be equal to a route built from the same components")
        void shouldEqualRouteFromSameComponents() {
            Route route = fullyResolvedProxyRoute();
            Route equalCopy = route.toBuilder().build();
            Route different = route.toBuilder().id(route.id() + "-other").build();

            assertValueObjectContract(route, equalCopy, different);
        }

        @Test
        @DisplayName("Should take the location rewriter into account")
        void shouldDiscriminateOnLocationRewriter() {
            Route route = fullyResolvedProxyRoute();
            Route equalCopy = route.toBuilder().build();
            Route withoutRewriter = route.toBuilder().locationRewriter(null).build();

            assertValueObjectContract(route, equalCopy, withoutRewriter);
        }

        private static void assertValueObjectContract(Route subject, Route equalCopy, Route different) {
            String rendered = subject.toString();
            assertAll("value-object contract",
                    () -> assertEquals(subject, equalCopy, "Routes built from equal components are equal"),
                    () -> assertEquals(subject.hashCode(), equalCopy.hashCode(), "Equal routes share a hashCode"),
                    () -> assertNotEquals(subject, different, "Routes with differing components are not equal"),
                    () -> assertTrue(rendered.startsWith("Route"), "The rendering names its own type: " + rendered),
                    () -> assertEquals(equalCopy.toString(), rendered, "Equal routes render equally"),
                    () -> assertNotEquals(different.toString(), rendered,
                            "The rendering tells two unequal routes apart"));
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
            assertEquals("route '" + id + "' must resolve exactly one terminal action (one of upstream, asset, redirect)",
                    thrown.getMessage(), "The refusal names the route and the invariant");
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
                    .effectiveSecurityFilter(SecurityFilterConfig.builder().allowedPaths(paths).build())
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
                    .effectiveSecurityFilter(SecurityFilterConfig.builder().allowedPaths(List.of("/allowed")).build())
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

    @Nested
    @DisplayName("RouteMatcher — the compiled match block")
    class RouteMatcherContract {

        @Test
        @DisplayName("Should match a prefix only on segment boundaries")
        void shouldMatchPrefixOnlyOnSegmentBoundaries() {
            var matcher = RouteMatcher.from(MatchConfig.builder().pathPrefix("/api").build());

            assertTrue(matcher.matchesPath("/api"), "Exact prefix matches");
            assertTrue(matcher.matchesPath("/api/users"), "Child path matches");
            assertFalse(matcher.matchesPath("/apiary"), "Non-boundary continuation does not match");
            assertFalse(matcher.isExact(), "A path_prefix matcher is not an exact matcher");
            assertEquals("/api", matcher.matchKey(), "The prefix is the match key");
        }

        @Test
        @DisplayName("Should match an exact path by un-normalized string equality only")
        void shouldMatchExactPathByEqualityOnly() {
            var matcher = RouteMatcher.from(MatchConfig.builder().path("/a").build());

            assertTrue(matcher.matchesPath("/a"), "The exact address matches");
            assertFalse(matcher.matchesPath("/a/"), "A trailing-slash variant is a distinct address");
            assertFalse(matcher.matchesPath("/a/b"), "A child path does not match an exact route");
            assertFalse(matcher.matchesPath("/ab"), "A leading-substring continuation does not match");
            assertTrue(matcher.isExact(), "A path matcher is an exact matcher");
            assertEquals("/a", matcher.matchKey(), "The exact path is the match key");
        }

        @Test
        @DisplayName("Should keep a trailing slash significant for an exact path that declares one")
        void shouldKeepTrailingSlashSignificantForExactPath() {
            var matcher = RouteMatcher.from(MatchConfig.builder().path("/a/").build());

            assertTrue(matcher.matchesPath("/a/"), "The declared trailing-slash address matches");
            assertFalse(matcher.matchesPath("/a"), "The slash-less variant is a distinct address");
            assertFalse(matcher.matchesPath("/a/b"), "A child path does not match, even below a slash");
        }

        @Test
        @DisplayName("Should apply the method, host and header matchers on top of an exact path")
        void shouldApplyAllMatchersToExactPath() {
            var matcher = RouteMatcher.from(MatchConfig.builder()
                    .path("/login")
                    .methods(List.of(HttpMethod.POST))
                    .build());

            assertTrue(matcher.matches("/login", HttpMethod.POST, null, Map.of()), "Path and method hold");
            assertFalse(matcher.matches("/login", HttpMethod.GET, null, Map.of()), "Wrong method fails");
            assertFalse(matcher.matches("/login/x", HttpMethod.POST, null, Map.of()), "A child path fails");
        }

        @Test
        @DisplayName("Should apply method, host, and header matchers with AND semantics")
        void shouldApplyAllMatchersWithAndSemantics() {
            var matcher = RouteMatcher.from(MatchConfig.builder()
                    .pathPrefix("/api")
                    .methods(List.of(HttpMethod.GET))
                    .host("gw.example")
                    .headers(List.of(HeaderMatcher.builder().name("X-Tenant").present(true).build()))
                    .build());
            Map<String, String> headers = Map.of("x-tenant", "acme");

            assertTrue(matcher.matches("/api/x", HttpMethod.GET, "gw.example", headers), "All matchers hold");
            assertFalse(matcher.matches("/api/x", HttpMethod.POST, "gw.example", headers), "Wrong method fails");
            assertFalse(matcher.matches("/api/x", HttpMethod.GET, "other.example", headers), "Wrong host fails");
            assertFalse(matcher.matches("/api/x", HttpMethod.GET, "gw.example", Map.of()), "Missing header fails");
        }

        @Test
        @DisplayName("Should require an exact header value when configured")
        void shouldRequireExactHeaderValue() {
            var matcher = RouteMatcher.from(MatchConfig.builder()
                    .pathPrefix("/api")
                    .headers(List.of(HeaderMatcher.builder().name("X-Env").value("prod").build()))
                    .build());

            assertTrue(matcher.matches("/api", HttpMethod.GET, null, Map.of("x-env", "prod")), "Exact value matches");
            assertFalse(matcher.matches("/api", HttpMethod.GET, null, Map.of("x-env", "dev")), "Wrong value fails");
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 12, count = 5)
        @DisplayName("Should match a mixed-case configured header name against the lower-case-keyed request headers")
        void shouldMatchMixedCaseHeaderNameCaseInsensitively(String value) {
            var presenceMatcher = headerMatcher(HeaderMatcher.builder().name("X-Tenant").present(true).build());
            var valueMatcher = headerMatcher(HeaderMatcher.builder().name("X-Tenant").value(value).build());
            Map<String, String> headers = Map.of("x-tenant", value);

            assertTrue(presenceMatcher.matches("/api", HttpMethod.GET, null, headers),
                    "A present: true matcher declared as X-Tenant finds the header keyed x-tenant");
            assertTrue(valueMatcher.matches("/api", HttpMethod.GET, null, headers),
                    "A value matcher declared as X-Tenant finds the header keyed x-tenant");
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 12, count = 5)
        @DisplayName("Should require the header's absence for present: false")
        void shouldRequireAbsenceForPresentFalse(String value) {
            var matcher = headerMatcher(HeaderMatcher.builder().name("X-Internal").present(false).build());

            assertTrue(matcher.matches("/api", HttpMethod.GET, null, Map.of()),
                    "present: false holds when the header is absent");
            assertFalse(matcher.matches("/api", HttpMethod.GET, null, Map.of("x-internal", value)),
                    "present: false fails when the header is present");
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 12, count = 5)
        @DisplayName("Should require both presence and value for present: true together with value")
        void shouldRequireBothPresenceAndValue(String value) {
            var matcher = headerMatcher(HeaderMatcher.builder().name("X-Env").present(true).value(value).build());

            assertTrue(matcher.matches("/api", HttpMethod.GET, null, Map.of("x-env", value)),
                    "The present header carrying the exact value matches");
            assertFalse(matcher.matches("/api", HttpMethod.GET, null, Map.of("x-env", value + "-other")),
                    "A present header carrying a different value fails");
            assertFalse(matcher.matches("/api", HttpMethod.GET, null, Map.of()),
                    "An absent header fails");
        }

        @ParameterizedTest
        @GeneratorsSource(generator = GeneratorType.LETTER_STRINGS, minSize = 3, maxSize = 12, count = 5)
        @DisplayName("Should compare header values case-sensitively")
        void shouldCompareHeaderValuesCaseSensitively(String label) {
            String configured = "v" + label.toLowerCase(Locale.ROOT);
            var matcher = headerMatcher(HeaderMatcher.builder().name("X-Env").value(configured).build());

            assertFalse(matcher.matches("/api", HttpMethod.GET, null,
                            Map.of("x-env", configured.toUpperCase(Locale.ROOT))),
                    "A value differing only in letter case does not match");
        }

        @Test
        @DisplayName("Should expose lower-case matcher header names, collapsing names that differ only in case")
        void shouldExposeLowerCaseMatcherHeaderNames() {
            var matcher = headerMatcher(
                    HeaderMatcher.builder().name("X-Tenant").present(true).build(),
                    HeaderMatcher.builder().name("x-TENANT").value("acme").build(),
                    HeaderMatcher.builder().name("X-Env").present(false).build());

            assertEquals(List.of("x-tenant", "x-env"), matcher.matchHeaderNames(),
                    "The names are lower-cased in declaration order, case-only duplicates collapsed");
        }

        private RouteMatcher headerMatcher(HeaderMatcher... headers) {
            return RouteMatcher.from(MatchConfig.builder().pathPrefix("/api").headers(List.of(headers)).build());
        }
    }

    @Nested
    @DisplayName("ProtocolProcessorRegistry — the boot-time processor selection")
    class ProtocolProcessorRegistryContract {

        private final ProtocolProcessorRegistry registry = new ProtocolProcessorRegistry();

        @Test
        @DisplayName("Should reuse one HTTP processor for HTTP and GraphQL routes")
        void shouldReuseHttpProcessorForGraphql() {
            ProtocolProcessor http = registry.require(Protocol.HTTP, "route-http");
            ProtocolProcessor graphql = registry.require(Protocol.GRAPHQL, "route-graphql");

            assertSame(http, graphql, "GraphQL must reuse the shared HTTP processor instance");
            assertEquals("http", http.id(), "The shared processor is the HTTP processor");
        }

        @Test
        @DisplayName("Should serve gRPC and WebSocket with their dedicated processors")
        void shouldServeGrpcAndWebSocketProcessors() {
            assertTrue(registry.supports(Protocol.GRPC), "gRPC is supported");
            assertEquals("grpc", registry.require(Protocol.GRPC, "grpc-route").id(),
                    "A gRPC route resolves the dedicated gRPC processor");
            assertTrue(registry.supports(Protocol.WEBSOCKET), "WebSocket is supported");
            assertEquals("websocket", registry.require(Protocol.WEBSOCKET, "ws-route").id(),
                    "A WebSocket route resolves the WebSocket processor");
        }

        @Test
        @DisplayName("HttpProtocolProcessor should serve every standard proxyable verb")
        void httpProcessorShouldServeStandardVerbs() {
            var processor = new HttpProtocolProcessor();

            assertTrue(processor.supports(HttpMethod.GET), "GET is a standard verb");
            assertTrue(processor.supports(HttpMethod.DELETE), "DELETE is a standard verb");
            assertEquals(HttpMethod.values().length, processor.standardMethods().size(),
                    "Every proxyable verb is standard");
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
                .securityHeaders(SecurityHeadersConfig.builder().build())
                .effectiveForward(ForwardConfig.builder().headersAllow(List.of("Accept")).build())
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
