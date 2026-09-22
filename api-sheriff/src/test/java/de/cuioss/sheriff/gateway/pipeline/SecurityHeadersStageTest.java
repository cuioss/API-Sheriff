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
package de.cuioss.sheriff.gateway.pipeline;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;


import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig.Cors;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig.HeaderMode;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig.HeaderModes;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig.Hsts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SecurityHeadersStage — stage 0 response headers, CORS origin handling and stage 2a route headers")
class SecurityHeadersStageTest {

    private static final String ACAO = "Access-Control-Allow-Origin";
    private static final String HSTS = "Strict-Transport-Security";
    private static final String NOSNIFF = "X-Content-Type-Options";
    private static final String FRAME_OPTIONS = "X-Frame-Options";
    private static final String CSP = "Content-Security-Policy";
    private static final String GLOBAL_POLICY = "default-src 'self'; frame-ancestors 'none'";

    @Nested
    @DisplayName("stage 0 — content_security_policy is served verbatim from the global block")
    class ContentSecurityPolicy {

        @Test
        @DisplayName("emits the configured Content-Security-Policy value verbatim")
        void emitsConfiguredPolicyVerbatim() {
            SecurityHeadersStage stage = new SecurityHeadersStage(
                    SecurityHeadersConfig.builder().contentSecurityPolicy(GLOBAL_POLICY).build());
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);

            stage.process(request);

            assertEquals(GLOBAL_POLICY, request.responseHeaders().get(CSP),
                    "the policy is served byte-for-byte as configured");
        }

        @Test
        @DisplayName("emits no Content-Security-Policy header when the block omits the key")
        void emitsNoPolicyWhenOmitted() {
            SecurityHeadersStage stage =
                    new SecurityHeadersStage(SecurityHeadersConfig.builder().frameDeny(true).build());
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);

            stage.process(request);

            assertAll("an omitted policy emits no header while the enabled headers still apply",
                    () -> assertNull(request.responseHeaders().get(CSP)),
                    () -> assertEquals("DENY", request.responseHeaders().get(FRAME_OPTIONS)));
        }
    }

    @Nested
    @DisplayName("header_modes — each gateway-owned header is seeded into the set-map or the default-map")
    class HeaderModeRouting {

        private final SecurityHeadersConfig allDefault = SecurityHeadersConfig.builder()
                .hsts(new Hsts(600, false))
                .contentTypeNosniff(true)
                .frameDeny(true)
                .contentSecurityPolicy(GLOBAL_POLICY)
                .headerModes(new HeaderModes(HeaderMode.DEFAULT, HeaderMode.DEFAULT, HeaderMode.DEFAULT,
                        HeaderMode.DEFAULT))
                .build();

        @Test
        @DisplayName("an omitted header_modes block seeds every header into the set-map")
        void omittedModesSeedSetMap() {
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);

            new SecurityHeadersStage(SecurityHeadersConfig.builder().frameDeny(true)
                    .contentSecurityPolicy(GLOBAL_POLICY).build()).process(request);

            assertAll("absent modes are exactly the overwrite behaviour",
                    () -> assertEquals("DENY", request.responseHeaders().get(FRAME_OPTIONS)),
                    () -> assertEquals(GLOBAL_POLICY, request.responseHeaders().get(CSP)),
                    () -> assertTrue(request.responseDefaultHeaders().isEmpty(),
                            "no header lands in the default-map without a default mode"));
        }

        @Test
        @DisplayName("routes each header by its own mode, and an absent key within a declared block means set")
        void routesEachHeaderByItsOwnMode() {
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);
            SecurityHeadersConfig mixed = SecurityHeadersConfig.builder()
                    .frameDeny(true)
                    .contentSecurityPolicy(GLOBAL_POLICY)
                    .headerModes(HeaderModes.builder().contentSecurityPolicy(HeaderMode.DEFAULT).build())
                    .build();

            new SecurityHeadersStage(mixed).process(request);

            assertAll("content_security_policy: default, frame_deny: (absent) set",
                    () -> assertEquals(GLOBAL_POLICY, request.responseDefaultHeaders().get(CSP)),
                    () -> assertNull(request.responseHeaders().get(CSP),
                            "a default-mode header never also lives in the set-map"),
                    () -> assertEquals("DENY", request.responseHeaders().get(FRAME_OPTIONS)),
                    () -> assertNull(request.responseDefaultHeaders().get(FRAME_OPTIONS)));
        }

        @Test
        @DisplayName("seeds every enabled header into the default-map when all four modes are default")
        void seedsAllDefaultHeadersIntoDefaultMap() {
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);

            new SecurityHeadersStage(allDefault).process(request);

            assertAll("every gateway-owned header follows its mode",
                    () -> assertEquals(Map.of(HSTS, "max-age=600", NOSNIFF, "nosniff", FRAME_OPTIONS, "DENY",
                            CSP, GLOBAL_POLICY), request.responseDefaultHeaders()),
                    () -> assertTrue(request.responseHeaders().isEmpty(),
                            "no gateway-owned security header remains in the set-map"));
        }

        @Test
        @DisplayName("stage 2a clears a default-mode global header from both maps before seeding the route block")
        void routeHeadersClearBothMapsBeforeSeeding() {
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);
            SecurityHeadersStage stage = new SecurityHeadersStage(allDefault);
            stage.process(request);

            stage.applyRouteHeaders(request, SecurityHeadersConfig.builder().frameDeny(true).build(), List.of());

            assertAll("the route block replaces the global block wholesale across both maps",
                    () -> assertEquals("DENY", request.responseHeaders().get(FRAME_OPTIONS),
                            "the route block's frame_deny has no mode, so it is set-mode now"),
                    () -> assertTrue(request.responseDefaultHeaders().isEmpty(),
                            "every default-mode global header is gone — none survives into the route's response"),
                    () -> assertNull(request.responseHeaders().get(CSP)));
        }

        @Test
        @DisplayName("merges both maps for a gateway-authored response")
        void mergesBothMapsForGatewayAuthoredResponse() {
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);
            new SecurityHeadersStage(SecurityHeadersConfig.builder()
                    .frameDeny(true)
                    .contentSecurityPolicy(GLOBAL_POLICY)
                    .headerModes(HeaderModes.builder().contentSecurityPolicy(HeaderMode.DEFAULT).build())
                    .build()).process(request);

            assertEquals(Map.of(FRAME_OPTIONS, "DENY", CSP, GLOBAL_POLICY), request.gatewayAuthoredResponseHeaders(),
                    "a response without an origin header carries both the set and the default header");
        }
    }

    @Test
    @DisplayName("reflects any presented origin when a wildcard origin is configured")
    void wildcardReflectsAnyOrigin() {
        // Arrange — a configured "*" is a real wildcard (credentials disabled); the request Origin is
        // never literally "*", so the stage must accept and reflect an arbitrary presented origin.
        SecurityHeadersStage stage = corsStage(List.of("*"), false);
        PipelineRequest request = corsRequest(HttpMethod.GET, "https://any.example", false);

        // Act
        stage.process(request);

        // Assert
        assertEquals("https://any.example", request.responseHeaders().get(ACAO));
    }

    @Test
    @DisplayName("emits CORS headers for an explicitly allow-listed origin")
    void exactOriginMatch() {
        // Arrange
        SecurityHeadersStage stage = corsStage(List.of("https://ok.example"), false);
        PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);

        // Act
        stage.process(request);

        // Assert
        assertEquals("https://ok.example", request.responseHeaders().get(ACAO));
    }

    @Nested
    @DisplayName("Vary on the CORS reflection")
    class VaryOnCorsReflection {

        private static final String VARY = "Vary";

        @Test
        @DisplayName("announces Vary: Origin whenever an origin is reflected")
        void reflectionAnnouncesVaryOrigin() {
            // Arrange — the reflected value IS the request's Origin, so the response differs per
            // origin. Without Vary a shared cache may hand one origin's ACAO to another; a public
            // cookie-less redirect is heuristically cacheable, which is where this bites.
            SecurityHeadersStage stage = corsStage(List.of("*"), false);
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://any.example", false);

            // Act
            stage.process(request);

            // Assert
            assertAll("a reflected origin is announced as a cache variant",
                    () -> assertEquals("https://any.example", request.responseHeaders().get(ACAO)),
                    () -> assertEquals("Origin", request.responseHeaders().get(VARY)));
        }

        @Test
        @DisplayName("adds no Vary when no origin is reflected")
        void noReflectionNoVary() {
            // THE CONTROL: Vary must follow the reflection, not the stage. A refused origin produces
            // no ACAO, so the response does not vary and must not claim to.
            SecurityHeadersStage stage = corsStage(List.of("https://ok.example"), false);
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://evil.example", false);

            stage.process(request);

            assertNull(request.responseHeaders().get(VARY),
                    "a response that carries no Access-Control-Allow-Origin does not vary on Origin");
        }

        @Test
        @DisplayName("stage 2a announces the route's match.headers names, whatever answers the request")
        void routeMatchHeadersAreAnnouncedAtStageTwoA() {
            // The names were previously merged only by the redirect writer, so a header-matched route
            // answered by the proxy relay, gRPC, an asset, a short-circuit or a rejection emitted no
            // Vary at all. The proxy relay is the worst of those: it forwards the UPSTREAM's cache
            // policy rather than forcing no-store, so a cacheable response could be reused for a
            // request that selected a different variant of the same route. Announcing at stage 2a
            // covers every terminal path at once, because they all write this accumulated map.
            SecurityHeadersStage stage = corsStage(List.of("https://ok.example"), false);
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);
            stage.process(request);

            stage.applyRouteHeaders(request, SecurityHeadersConfig.builder().frameDeny(true).build(),
                    List.of("X-Tenant", "X-Channel"));

            assertAll("both matcher names ride the accumulated map, merged onto the CORS Origin",
                    () -> assertEquals("Origin, X-Tenant, X-Channel", request.responseHeaders().get(VARY)),
                    () -> assertEquals("DENY", request.responseHeaders().get(FRAME_OPTIONS),
                            "the route block still governs the owned headers"));
        }

        @Test
        @DisplayName("stage 2a adds no Vary for a route that matches on no header")
        void noMatchHeadersNoVary() {
            // THE CONTROL: the announcement follows the matcher, not the stage. Without it every
            // routed response would claim a variance it does not have, which costs cache hits.
            SecurityHeadersStage stage = new SecurityHeadersStage(
                    SecurityHeadersConfig.builder().frameDeny(true).build());
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);
            stage.process(request);

            stage.applyRouteHeaders(request, SecurityHeadersConfig.builder().frameDeny(true).build(),
                    List.of());

            assertNull(request.responseHeaders().get(VARY),
                    "a route selected on the path alone produces one response per address");
        }

        @Test
        @DisplayName("merges into an existing Vary rather than replacing it")
        void mergesIntoExistingVary() {
            assertAll("the merge rule",
                    () -> assertEquals("Origin", SecurityHeadersStage.mergedVary(null, List.of("Origin"))),
                    () -> assertEquals("Origin", SecurityHeadersStage.mergedVary("  ", List.of("Origin"))),
                    () -> assertEquals("X-Tenant, Origin",
                            SecurityHeadersStage.mergedVary("X-Tenant", List.of("Origin")),
                            "an existing name is kept — the redirect path lists its match.headers there"),
                    () -> assertEquals("Origin", SecurityHeadersStage.mergedVary("Origin", List.of("Origin")),
                            "Vary is a set; a repeat only misleads"),
                    () -> assertEquals("origin", SecurityHeadersStage.mergedVary("origin", List.of("Origin")),
                            "field names are case-insensitive, so no duplicate is added"),
                    () -> assertEquals("*", SecurityHeadersStage.mergedVary("*", List.of("Origin")),
                            "* already varies on everything; adding a name would WEAKEN it"),
                    () -> assertEquals("X-A, X-B, Origin",
                            SecurityHeadersStage.mergedVary("X-A, X-B", List.of("Origin"))));
        }
    }

    @Test
    @DisplayName("emits no CORS header for an origin that is neither listed nor wildcarded")
    void disallowedOriginEmitsNoCorsHeader() {
        // Arrange
        SecurityHeadersStage stage = corsStage(List.of("https://ok.example"), false);
        PipelineRequest request = corsRequest(HttpMethod.GET, "https://evil.example", false);

        // Act
        stage.process(request);

        // Assert
        assertNull(request.responseHeaders().get(ACAO));
    }

    @Test
    @DisplayName("short-circuits a wildcard-origin preflight with 204 and the reflected origin")
    void wildcardPreflightShortCircuits() {
        // Arrange
        SecurityHeadersStage stage = corsStage(List.of("*"), false);
        PipelineRequest request = corsRequest(HttpMethod.OPTIONS, "https://any.example", true);

        // Act
        stage.process(request);

        // Assert
        assertEquals("https://any.example", request.responseHeaders().get(ACAO));
        assertTrue(request.shortCircuitStatus().isPresent(), "a CORS preflight must short-circuit");
        assertEquals(204, request.shortCircuitStatus().orElseThrow().intValue());
    }

    @Test
    @DisplayName("short-circuits a preflight at stage 0 carrying the global security headers")
    void preflightShortCircuitsWithGlobalBlock() {
        Cors cors = Cors.builder().enabled(Boolean.TRUE).allowedOrigins(List.of("https://ok.example"))
                .allowedMethods(List.of("GET")).build();
        SecurityHeadersStage stage = new SecurityHeadersStage(SecurityHeadersConfig.builder()
                .hsts(new Hsts(31536000, true)).frameDeny(true).cors(cors).build());
        PipelineRequest request = corsRequest(HttpMethod.OPTIONS, "https://ok.example", true);

        stage.process(request);

        assertAll("the preflight is answered before route selection, with the global block",
                () -> assertEquals(204, request.shortCircuitStatus().orElseThrow().intValue()),
                () -> assertEquals("max-age=31536000; includeSubDomains",
                        request.responseHeaders().get(HSTS)),
                () -> assertEquals("DENY", request.responseHeaders().get(FRAME_OPTIONS)),
                () -> assertEquals("GET", request.responseHeaders().get("Access-Control-Allow-Methods")));
    }

    @Nested
    @DisplayName("stage 2a — applyRouteHeaders replaces the global block with the route's block")
    class RouteHeaders {

        private final SecurityHeadersConfig global = SecurityHeadersConfig.builder()
                .hsts(new Hsts(31536000, true))
                .contentTypeNosniff(true)
                .frameDeny(true)
                .contentSecurityPolicy(GLOBAL_POLICY)
                .cors(Cors.builder().enabled(Boolean.TRUE).allowedOrigins(List.of("https://ok.example")).build())
                .build();

        @Test
        @DisplayName("drops the global Content-Security-Policy when the anchor block declares none")
        void anchorBlockWithoutPolicyDropsGlobalPolicy() {
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);
            SecurityHeadersStage stage = new SecurityHeadersStage(global);
            stage.process(request);
            assertEquals(GLOBAL_POLICY, request.responseHeaders().get(CSP),
                    "precondition: stage 0 seeded the global policy");

            stage.applyRouteHeaders(request, SecurityHeadersConfig.builder().frameDeny(true).build(), List.of());

            assertAll("the anchor block replaces the global block wholesale, policy included",
                    () -> assertNull(request.responseHeaders().get(CSP),
                            "the global policy does not survive an anchor block that omits it"),
                    () -> assertEquals("DENY", request.responseHeaders().get(FRAME_OPTIONS)));
        }

        @Test
        @DisplayName("replaces the global Content-Security-Policy with the anchor block's own policy")
        void anchorPolicyReplacesGlobalPolicy() {
            String anchorPolicy = "default-src 'none'; img-src 'self'";
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);
            SecurityHeadersStage stage = new SecurityHeadersStage(global);
            stage.process(request);

            stage.applyRouteHeaders(request,
                    SecurityHeadersConfig.builder().contentSecurityPolicy(anchorPolicy).build(), List.of());

            assertAll("exactly one policy, the anchor's, is on the response",
                    () -> assertEquals(anchorPolicy, request.responseHeaders().get(CSP)),
                    () -> assertEquals(1, request.responseHeaders().keySet().stream()
                                    .filter(CSP::equalsIgnoreCase).count(),
                            "the global policy is removed, never merged alongside the anchor's"));
        }

        @Test
        @DisplayName("replaces the security names wholesale — an anchor block with only frame_deny drops HSTS and nosniff")
        void replacesSecurityNamesWholesale() {
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);
            SecurityHeadersStage stage = new SecurityHeadersStage(global);
            stage.process(request);
            request.responseHeaders().put("Allow", "GET");

            stage.applyRouteHeaders(request, SecurityHeadersConfig.builder().frameDeny(true).build(), List.of());

            assertAll("only the gateway-owned security names are replaced",
                    () -> assertEquals("DENY", request.responseHeaders().get(FRAME_OPTIONS),
                            "the route block's frame_deny is seeded"),
                    () -> assertNull(request.responseHeaders().get(HSTS),
                            "the global HSTS is dropped — no key-by-key merge"),
                    () -> assertNull(request.responseHeaders().get(NOSNIFF),
                            "the global nosniff is dropped — no key-by-key merge"),
                    () -> assertEquals("https://ok.example", request.responseHeaders().get(ACAO),
                            "the global CORS header is untouched"),
                    () -> assertEquals("GET", request.responseHeaders().get("Allow"),
                            "a non-security entry is untouched"));
        }

        @Test
        @DisplayName("seeds every header the route block enables")
        void seedsEveryEnabledRouteHeader() {
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);
            SecurityHeadersStage stage =
                    new SecurityHeadersStage(SecurityHeadersConfig.builder().frameDeny(true).build());
            stage.process(request);

            stage.applyRouteHeaders(request, SecurityHeadersConfig.builder()
                    .hsts(new Hsts(600, false)).contentTypeNosniff(true).build(), List.of());

            assertAll("the route block governs the response from stage 2a on",
                    () -> assertEquals("max-age=600", request.responseHeaders().get(HSTS)),
                    () -> assertEquals("nosniff", request.responseHeaders().get(NOSNIFF)),
                    () -> assertNull(request.responseHeaders().get(FRAME_OPTIONS),
                            "the global frame_deny does not survive a route block that omits it"));
        }

        /**
         * The seeding half of the stage and its owning half are two lists that must name the same
         * headers: {@code applyResponseHeaders} decides what stage 0 puts on the response, and the set
         * behind {@code isGatewayOwned} decides what stage 2a takes off again. A header added to the
         * first but not the second keeps its GLOBAL value on an anchored route, which is exactly the
         * leak stage 2a exists to prevent.
         * <p>
         * So this guard asserts the SURVIVING KEY SET rather than the four names one by one. Naming
         * them here would be a third copy of a list the stage already holds twice, and a copy cannot
         * observe a fifth header added to only one of the two halves — it would keep passing while the
         * leak shipped. Comparing the whole key set derives both halves from the code under test: any
         * header stage 0 seeds and stage 2a does not own survives the call and fails here, whatever it
         * is called.
         * <p>
         * Both maps are asserted because a {@code default}-mode header lands in the default-map, so a
         * guard reading only the set-map would miss half the surface. The block below therefore splits
         * the four headers across both modes, and the precondition checks both maps were actually
         * populated — an assertion that passes because nothing was seeded proves nothing.
         */
        /**
         * THE MODEL GUARD, and it closes a different gap from the one below. That test derives the
         * seed half and the own half from the code under test, so the two cannot drift apart — but
         * both could drift away from the CONFIGURATION MODEL together: a fifth header property added
         * to {@link SecurityHeadersConfig} would bind, validate and be documented while the stage
         * simply never seeded it, and every assertion here would still pass, because the fixture
         * below enumerates its four headers by hand.
         * <p>
         * {@code HeaderModes} is the set to derive from: it exists to carry exactly one precedence
         * entry per gateway-owned header, so its record components ARE the owned set as the model
         * defines it. Comparing the count of headers the stage actually emits for an all-enabled
         * block against that component count ties the runtime to the model without naming a single
         * header here.
         */
        @Test
        @DisplayName("seeds exactly as many headers as the model declares modes for")
        void seedsOneHeaderPerDeclaredMode() {
            // Arrange — every header the model knows of, enabled, and no CORS block, so the response
            // maps hold gateway-owned names and nothing else.
            SecurityHeadersConfig everyHeader = SecurityHeadersConfig.builder()
                    .hsts(new Hsts(31536000, true))
                    .contentTypeNosniff(true)
                    .frameDeny(true)
                    .contentSecurityPolicy(GLOBAL_POLICY)
                    .build();
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);

            // Act
            new SecurityHeadersStage(everyHeader).process(request);

            // Assert
            int seeded = request.responseHeaders().size() + request.responseDefaultHeaders().size();
            int declaredModes = SecurityHeadersConfig.HeaderModes.class.getRecordComponents().length;
            assertEquals(declaredModes, seeded,
                    "the stage seeds one header per mode the model declares. A mismatch means a header"
                            + " property was added to SecurityHeadersConfig (and to HeaderModes) without"
                            + " the stage learning to emit it — it would bind and validate and never"
                            + " reach a response — or a mode was declared for something the stage does"
                            + " not treat as a gateway-owned header");
        }

        @Test
        @DisplayName("removes every gateway-owned name when the route resolves no block at all — whatever stage 0 seeded")
        void removesSecurityNamesWhenRouteHasNoBlock() {
            // Arrange
            SecurityHeadersConfig everyHeaderBothModes = SecurityHeadersConfig.builder()
                    .hsts(new Hsts(31536000, true))
                    .contentTypeNosniff(true)
                    .frameDeny(true)
                    .contentSecurityPolicy(GLOBAL_POLICY)
                    .headerModes(HeaderModes.builder()
                            .contentTypeNosniff(HeaderMode.DEFAULT)
                            .contentSecurityPolicy(HeaderMode.DEFAULT)
                            .build())
                    .cors(Cors.builder().enabled(Boolean.TRUE)
                            .allowedOrigins(List.of("https://ok.example")).build())
                    .build();
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);
            SecurityHeadersStage stage = new SecurityHeadersStage(everyHeaderBothModes);
            stage.process(request);
            assertAll("precondition: stage 0 populated both maps, so the assertion below is not vacuous",
                    () -> assertFalse(request.responseHeaders().isEmpty()),
                    () -> assertFalse(request.responseDefaultHeaders().isEmpty()));

            // Act
            stage.applyRouteHeaders(request, null, List.of());

            // Assert
            assertAll("a null route block leaves nothing behind but the CORS headers stage 2a never touches",
                    () -> assertEquals(Set.of(ACAO, "Vary"), request.responseHeaders().keySet(),
                            "every gateway-owned name stage 0 seeded into the set-map is gone, and only"
                                    + " the CORS reflection remains — BOTH halves of it. Vary belongs with"
                                    + " Access-Control-Allow-Origin and must survive stage 2a for the same"
                                    + " reason: a reflected origin that outlives its own variance"
                                    + " announcement is the cache-poisoning shape the Vary exists to close"),
                    () -> assertTrue(request.responseDefaultHeaders().isEmpty(),
                            "the default-map carried two gateway-owned names and must be emptied too"),
                    () -> assertEquals("https://ok.example", request.responseHeaders().get(ACAO)));
        }

        @Test
        @DisplayName("re-seeds an identical block unchanged when the route resolves the global block")
        void keepsGlobalValuesWhenRouteResolvesGlobalBlock() {
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);
            SecurityHeadersStage stage = new SecurityHeadersStage(global);
            stage.process(request);
            Map<String, String> afterStageZero = Map.copyOf(request.responseHeaders());

            stage.applyRouteHeaders(request, global, List.of());

            assertEquals(afterStageZero, Map.copyOf(request.responseHeaders()),
                    "an unanchored route resolves the global block, so stage 2a changes nothing");
        }

        @Test
        @DisplayName("leaves the short-circuit state untouched")
        void leavesShortCircuitUntouched() {
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);

            new SecurityHeadersStage(global).applyRouteHeaders(request, global, List.of());

            assertTrue(request.shortCircuitStatus().isEmpty(), "stage 2a never short-circuits a request");
        }
    }

    @Nested
    @DisplayName("applyPortalHeaders — the portal CSP and nosniff are forced in set-mode over any global block")
    class PortalHeaders {

        private static final String PORTAL_POLICY =
                "default-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

        @Test
        @DisplayName("replaces a global Content-Security-Policy declared in set mode")
        void replacesSetModeGlobalPolicy() {
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);
            new SecurityHeadersStage(SecurityHeadersConfig.builder().contentSecurityPolicy(GLOBAL_POLICY).build())
                    .process(request);

            SecurityHeadersStage.applyPortalHeaders(request);

            assertAll("the portal policy replaces the global one in the set-map",
                    () -> assertEquals(PORTAL_POLICY, request.responseHeaders().get(CSP)),
                    () -> assertNull(request.responseDefaultHeaders().get(CSP)),
                    () -> assertEquals(PORTAL_POLICY, SecurityHeadersStage.PORTAL_CONTENT_SECURITY_POLICY,
                            "the published constant is exactly the hardened portal policy, without 'unsafe-inline'"),
                    () -> assertFalse(SecurityHeadersStage.PORTAL_CONTENT_SECURITY_POLICY.contains("unsafe-inline"),
                            "the portal policy never permits inline script or style"));
        }

        @Test
        @DisplayName("moves a global Content-Security-Policy declared in default mode into the set-map, once")
        void replacesDefaultModeGlobalPolicy() {
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);
            new SecurityHeadersStage(SecurityHeadersConfig.builder()
                    .contentSecurityPolicy(GLOBAL_POLICY)
                    .contentTypeNosniff(true)
                    .headerModes(HeaderModes.builder().contentSecurityPolicy(HeaderMode.DEFAULT)
                            .contentTypeNosniff(HeaderMode.DEFAULT).build())
                    .build()).process(request);

            SecurityHeadersStage.applyPortalHeaders(request);

            assertAll("each name lives in exactly one map afterwards, the set-map",
                    () -> assertEquals(PORTAL_POLICY, request.responseHeaders().get(CSP)),
                    () -> assertEquals("nosniff", request.responseHeaders().get(NOSNIFF)),
                    () -> assertFalse(request.responseDefaultHeaders().containsKey(CSP),
                            "the operator's default-mode policy must not survive to be applied a second time"),
                    () -> assertFalse(request.responseDefaultHeaders().containsKey(NOSNIFF)),
                    () -> assertEquals(PORTAL_POLICY, request.gatewayAuthoredResponseHeaders().get(CSP),
                            "the merged gateway-authored view carries only the portal policy"));
        }

        @Test
        @DisplayName("keeps HSTS and X-Frame-Options at their resolved block and mode")
        void keepsOtherGatewayOwnedHeaders() {
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);
            new SecurityHeadersStage(SecurityHeadersConfig.builder()
                    .hsts(new Hsts(600, true))
                    .frameDeny(true)
                    .contentSecurityPolicy(GLOBAL_POLICY)
                    .headerModes(HeaderModes.builder().frameDeny(HeaderMode.DEFAULT).build())
                    .build()).process(request);
            request.responseHeaders().put(ACAO, "https://ok.example");

            SecurityHeadersStage.applyPortalHeaders(request);

            assertAll("only the two portal-owned names are touched",
                    () -> assertEquals("max-age=600; includeSubDomains", request.responseHeaders().get(HSTS)),
                    () -> assertEquals("DENY", request.responseDefaultHeaders().get(FRAME_OPTIONS),
                            "a default-mode frame option stays default-mode"),
                    () -> assertNull(request.responseHeaders().get(FRAME_OPTIONS)),
                    () -> assertEquals("https://ok.example", request.responseHeaders().get(ACAO),
                            "non-owned entries such as a CORS reflection are untouched"));
        }

        @Test
        @DisplayName("forces nosniff even when the global block disabled it")
        void forcesNosniffWhenGlobalBlockDisabledIt() {
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);
            new SecurityHeadersStage(SecurityHeadersConfig.builder().contentTypeNosniff(false).frameDeny(true).build())
                    .process(request);

            SecurityHeadersStage.applyPortalHeaders(request);

            assertAll("nosniff and the portal policy are present although the global block emitted neither",
                    () -> assertEquals("nosniff", request.responseHeaders().get(NOSNIFF)),
                    () -> assertEquals(PORTAL_POLICY, request.responseHeaders().get(CSP)));
        }

        @Test
        @DisplayName("removes a differently-cased copy of either name before seeding")
        void removesDifferentlyCasedNames() {
            PipelineRequest request = corsRequest(HttpMethod.GET, "https://ok.example", false);
            request.responseHeaders().put("content-security-policy", GLOBAL_POLICY);
            request.responseDefaultHeaders().put("x-content-type-options", "sniff");

            SecurityHeadersStage.applyPortalHeaders(request);

            assertAll("exactly one value per name remains, the portal's",
                    () -> assertEquals(Set.of(CSP, NOSNIFF), request.responseHeaders().keySet()),
                    () -> assertTrue(request.responseDefaultHeaders().isEmpty()));
        }
    }

    private static SecurityHeadersStage corsStage(List<String> allowedOrigins, boolean allowCredentials) {
        Cors cors = Cors.builder()
                .enabled(Boolean.TRUE)
                .allowedOrigins(allowedOrigins)
                .allowedMethods(List.of("GET", "POST"))
                .allowCredentials(allowCredentials)
                .build();
        return new SecurityHeadersStage(
                SecurityHeadersConfig.builder().cors(cors).build());
    }

    private static PipelineRequest corsRequest(HttpMethod method, String origin, boolean preflight) {
        Map<String, List<String>> headers = preflight
                ? Map.of("origin", List.of(origin), "access-control-request-method", List.of("GET"))
                : Map.of("origin", List.of(origin));
        return PipelineRequest.builder()
                .method(method)
                .requestPath("/api")
                .queryParameters(List.of())
                .headers(headers)
                .build();
    }
}
