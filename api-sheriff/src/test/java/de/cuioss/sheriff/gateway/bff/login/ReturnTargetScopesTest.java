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
package de.cuioss.sheriff.gateway.bff.login;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.sheriff.gateway.pipeline.BasicChecksStage;
import de.cuioss.sheriff.gateway.pipeline.CanonicalPathGuard;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.pipeline.RouteSelectionStage;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.sheriff.gateway.routing.RouteTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link ReturnTargetScopes}: a {@code /auth/login?returnUrl=} target resolves to the
 * {@code neededScopes} of the authenticated route it lands on, and to {@code oidc.scopes} in every
 * other case.
 * <p>
 * The scope names are literals on purpose: each route's set must be distinguishable from every other
 * route's and from {@code oidc.scopes}, so that an assertion names which route was selected.
 */
@DisplayName("ReturnTargetScopes — login scope set resolved from the return target")
class ReturnTargetScopesTest {

    private static final String ORIGIN = "https://gw.example.com";
    private static final Set<String> OIDC_SCOPES = Set.of("openid", "profile", "email");
    private static final Set<String> SPECIAL_SCOPES = Set.of("openid", "profile", "email", "special:read");
    private static final Set<String> APP_SCOPES = Set.of("openid", "profile", "email", "app:read");
    private static final Set<String> REPORTS_SCOPES = Set.of("openid", "profile", "email", "reports:read");
    private static final Set<String> API_SCOPES = Set.of("openid", "profile", "email", "api:read");
    private static final Set<String> HEADER_SCOPES = Set.of("openid", "profile", "email", "tenant:read");
    private static final Set<String> PUBLIC_SCOPES = Set.of("openid", "profile", "email", "public:read");

    /**
     * The table in route-selection order, exactly as the route-table builder freezes it: every exact
     * route first, then prefixes longest-first.
     */
    private static final List<Route> ROUTES = List.of(
            route("special", MatchConfig.builder().path("/api/special").build(), Require.SESSION, SPECIAL_SCOPES),
            route("reports", MatchConfig.builder().pathPrefix("/app/reports").build(), Require.SESSION,
                    REPORTS_SCOPES),
            route("tenant", MatchConfig.builder().pathPrefix("/tenant")
                            .headers(List.of(new MatchConfig.HeaderMatcher("X-Tenant", null, "acme"))).build(),
                    Require.SESSION, HEADER_SCOPES),
            route("public", MatchConfig.builder().pathPrefix("/public").build(), Require.NONE, PUBLIC_SCOPES),
            route("app", MatchConfig.builder().pathPrefix("/app").build(), Require.SESSION, APP_SCOPES),
            route("api", MatchConfig.builder().pathPrefix("/api").build(), Require.BEARER, API_SCOPES),
            route("tenant-fallback", MatchConfig.builder().pathPrefix("/tenant").build(), Require.SESSION,
                    APP_SCOPES));

    private final ReturnTargetScopes resolver = new ReturnTargetScopes(new RouteTable(ROUTES), ORIGIN, OIDC_SCOPES);

    /**
     * The route the edge's inbound pipeline selects for a {@code GET} navigation to {@code returnUrl}:
     * its raw path (the query never takes part in selection) through the strict stage-1 floor, the
     * canonical-path guard and stage-2 route selection over {@link #ROUTES}, wired in the edge's order.
     */
    private static Route selectedByTheInboundPipeline(String returnUrl) {
        SecurityConfiguration strict = SecurityProfile.STRICT.preset();
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath(URI.create(returnUrl).getRawPath())
                .queryParameters(List.of())
                .headers(Map.of())
                .build();
        new BasicChecksStage(strict, new SecurityEventCounter(), null, strict).process(request);
        new CanonicalPathGuard().process(request);
        new RouteSelectionStage(ROUTES).process(request);
        return Objects.requireNonNull(request.selectedRoute(), "the pipeline selected no route");
    }

    private static Route route(String id, MatchConfig match, Require require, Set<String> neededScopes) {
        return Route.builder()
                .id(id)
                .match(match)
                .effectiveAuth(AuthConfig.builder().require(require).build())
                .effectiveAllowedMethods(Set.of(HttpMethod.GET))
                .upstream(new ResolvedUpstream("https", "upstream.example", 443, ""))
                .neededScopes(neededScopes)
                .build();
    }

    @Nested
    @DisplayName("A target landing on an authenticated route")
    class AuthenticatedRoute {

        @Test
        @DisplayName("Should resolve a session route to its neededScopes (oidc.scopes united with the endpoint's)")
        void shouldResolveSessionRoute() {
            assertEquals(APP_SCOPES, resolver.resolve("/app/orders"));
        }

        @Test
        @DisplayName("Should resolve a bearer route to its neededScopes too")
        void shouldResolveBearerRoute() {
            assertEquals(API_SCOPES, resolver.resolve("/api/orders"));
        }

        @Test
        @DisplayName("Should resolve a same-origin absolute URL like its relative path")
        void shouldResolveAbsoluteSameOriginUrl() {
            assertEquals(APP_SCOPES, resolver.resolve(ORIGIN + "/app/orders"));
        }

        @ParameterizedTest(name = "\"{0}\" matches on its path alone")
        @ValueSource(strings = {"/app/orders?tab=open&page=2", "/app/orders#section", "/app/orders?next=/public"})
        @DisplayName("Should ignore the query and fragment when matching")
        void shouldIgnoreQueryForMatching(String returnUrl) {
            assertEquals(APP_SCOPES, resolver.resolve(returnUrl));
        }

        @Test
        @DisplayName("Should resolve the longer prefix route over the shorter one covering the same path")
        void shouldResolveLongerPrefixRoute() {
            assertEquals(REPORTS_SCOPES, resolver.resolve("/app/reports/monthly"));
        }

        /**
         * Stage-1 normalization folds an interior run of {@code /} before route selection, so a
         * navigation to {@code /app//reports} is selected as {@code /app/reports}. The same-origin
         * check admits these targets — it refuses only a leading {@code //} — so the resolver must
         * fold them the same way, or it would match the literal path against the shorter {@code /app}
         * prefix and resolve a different route than the one the browser lands on.
         */
        @ParameterizedTest(name = "\"{0}\" selects the route stage-1 normalization selects")
        @ValueSource(strings = {"/app//reports", "/app///reports/monthly", "/app//reports//",
                ORIGIN + "/app//reports", "/app//reports?tab=open"})
        @DisplayName("Should fold interior empty segments the way stage-1 normalization does before matching")
        void shouldFoldInteriorEmptySegments(String returnUrl) {
            assertAll(
                    () -> assertTrue(PendingAuthorizationRecord.sameOrigin(returnUrl, ORIGIN),
                            "the same-origin check admits an interior empty segment"),
                    () -> assertEquals(REPORTS_SCOPES, resolver.resolve(returnUrl),
                            "the folded path selects the longer /app/reports prefix, not /app"));
        }

        /**
         * The resolver's fold is a re-statement of what the inbound pipeline does, so it is checked
         * against the pipeline itself rather than against an expected set written here: each target's
         * raw path is driven through the real stage-1 floor ({@link BasicChecksStage}), the
         * canonical-path guard and stage-2 {@link RouteSelectionStage} over the same routes, and the
         * scopes of the route the pipeline selects must be the scopes the resolver returns. A resolver
         * that stopped folding — or a pipeline that started folding differently — selects a different
         * route on one side only and fails here, which an expected-set assertion cannot notice.
         */
        @ParameterizedTest(name = "\"{0}\" resolves to the scopes of the route the inbound pipeline selects")
        @ValueSource(strings = {"/app//reports", "/app///reports/monthly", "/app//reports//",
                ORIGIN + "/app//reports", "/app//reports?tab=open", "/app/reports", "/app/orders"})
        @DisplayName("Should resolve the scopes of the route stage-1 normalization and route selection select")
        void shouldMatchTheRouteTheInboundPipelineSelects(String returnUrl) {
            Route selected = selectedByTheInboundPipeline(returnUrl);

            Set<String> selectedScopes = selected.effectiveAuth().require() == Require.NONE
                    ? OIDC_SCOPES
                    : selected.neededScopes();
            assertEquals(selectedScopes, resolver.resolve(returnUrl),
                    () -> "the resolver must land on the route the pipeline selects for " + returnUrl
                            + ", which is " + selected.id());
        }

        @Test
        @DisplayName("Should fall back for a dot-segment path instead of normalizing it onto a route")
        void shouldFallBackForDotSegmentPath() {
            assertEquals(OIDC_SCOPES, resolver.resolve("/public/../app/orders"),
                    "the same-origin check refuses a dot segment (the shared Location review), so the target "
                            + "is not a usable return URL and selects no route");
        }
    }

    @Nested
    @DisplayName("Route-table order")
    class Ordering {

        @Test
        @DisplayName("Should select an exact route before a prefix route covering the same path")
        void shouldPreferExactRoute() {
            assertEquals(SPECIAL_SCOPES, resolver.resolve("/api/special"));
        }

        @Test
        @DisplayName("Should fall through to the prefix route for a sibling of the exact path")
        void shouldFallThroughToPrefix() {
            assertEquals(API_SCOPES, resolver.resolve("/api/special/nested"));
        }

        @Test
        @DisplayName("Should never select a header-matched route, and fall through to the next matching route")
        void shouldSkipHeaderMatchedRoute() {
            assertEquals(APP_SCOPES, resolver.resolve("/tenant/dashboard"),
                    "a header matcher cannot be evaluated before the request happens, so the "
                            + "header-free fallback route is selected instead");
        }
    }

    @Nested
    @DisplayName("Every other target falls back to oidc.scopes")
    class Fallback {

        @Test
        @DisplayName("Should fall back for a require:none route, whatever its endpoint scopes")
        void shouldFallBackForUnauthenticatedRoute() {
            assertEquals(OIDC_SCOPES, resolver.resolve("/public/landing"));
        }

        @Test
        @DisplayName("Should fall back when no route matches")
        void shouldFallBackForNoMatch() {
            assertEquals(OIDC_SCOPES, resolver.resolve("/unrouted"));
        }

        @Test
        @DisplayName("Should fall back when no return URL is supplied")
        void shouldFallBackForAbsent() {
            assertEquals(OIDC_SCOPES, resolver.resolve(null));
        }

        @ParameterizedTest(name = "\"{0}\" is not a same-origin target")
        @ValueSource(strings = {"https://evil.example.com/app/orders", "//evil.example.com/app", "/\\evil.example.com",
                "", " ", "javascript:alert(1)"})
        @DisplayName("Should fall back for a cross-origin, schema-relative, blank or unparseable target")
        void shouldFallBackForCrossOrigin(String returnUrl) {
            assertEquals(OIDC_SCOPES, resolver.resolve(returnUrl));
        }

        @ParameterizedTest(name = "\"{0}\" is not a canonical path")
        @ValueSource(strings = {"/app%2forders", "/app%5Corders", "/app;jsessionid=1", "/../app/orders", "/app/a b"})
        @DisplayName("Should fall back for a non-canonical or unparseable path")
        void shouldFallBackForNonCanonicalPath(String returnUrl) {
            assertEquals(OIDC_SCOPES, resolver.resolve(returnUrl));
        }

        /**
         * Every value below would select the authenticated {@code app} route if it reached route
         * selection: its decoded path, or the path a client resolves it to, lies under {@code /app}.
         * The resolver keeps no encoded-separator or dot-segment check of its own, so the fallback is
         * owed entirely to the same-origin check refusing each one first — which the first assertion
         * pins directly, so a fallback reached any other way cannot pass for it.
         */
        @ParameterizedTest(name = "\"{0}\" is refused by the same-origin check")
        @ValueSource(strings = {"/app%2forders", "/app%2Forders", "/app/%5c/orders", "/app/%5C/orders",
                "/app/./orders", "/app/%2e/orders", "/app/%2E%2E/app/orders", "/x/../app/orders",
                ORIGIN + "/app/%2e%2e/app/orders", ORIGIN + "/x/../app/orders", ORIGIN + "/app%2forders"})
        @DisplayName("Should fall back for an encoded separator or a dot segment, refused by the same-origin check alone")
        void shouldFallBackForPathTheSameOriginCheckRefuses(String returnUrl) {
            assertAll(
                    () -> assertFalse(PendingAuthorizationRecord.sameOrigin(returnUrl, ORIGIN),
                            "the shared Location review behind sameOrigin refuses the path"),
                    () -> assertEquals(OIDC_SCOPES, resolver.resolve(returnUrl),
                            "a refused target selects no route"));
        }

        @Test
        @DisplayName("Should treat the bare gateway origin as the root path, which no route here covers")
        void shouldResolveBareOrigin() {
            assertEquals(OIDC_SCOPES, resolver.resolve(ORIGIN),
                    "the bare origin is the root path, which no route in this table covers");
        }
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("Should resolve everything to oidc.scopes over an empty route table")
        void shouldFallBackOverEmptyTable() {
            ReturnTargetScopes empty = new ReturnTargetScopes(new RouteTable(List.of()), ORIGIN, OIDC_SCOPES);

            assertEquals(OIDC_SCOPES, empty.resolve("/app/orders"));
        }

        @Test
        @DisplayName("Should reject absent constructor arguments")
        void shouldRejectNulls() {
            RouteTable table = new RouteTable(List.of());
            assertThrows(NullPointerException.class, () -> new ReturnTargetScopes(null, ORIGIN, OIDC_SCOPES));
            assertThrows(NullPointerException.class, () -> new ReturnTargetScopes(table, null, OIDC_SCOPES));
            assertThrows(NullPointerException.class, () -> new ReturnTargetScopes(table, ORIGIN, null));
        }
    }
}
