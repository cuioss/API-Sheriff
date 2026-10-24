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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AnchorType;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.CookieAllow;
import de.cuioss.sheriff.gateway.config.model.CookiesConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.RedirectConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.WebSocketConfig;
import de.cuioss.sheriff.gateway.config.validation.ConfigValidator;
import de.cuioss.sheriff.gateway.http.GatewayCookies;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the {@code cookies}-block rules the {@link ConfigValidator} runs through
 * {@link CookieRules}: one refusal and one accepted control per rule, and — for the rules judging the
 * block's content — a refusal at every scope the block is declarable at.
 * <p>
 * Every case drives the full default validator, so a rule dropped from its registration fails here as
 * surely as a rule whose body is removed.
 */
@EnableGeneratorController
@DisplayName("ConfigValidator — cookies")
class CookieRulesTest {

    private static final String ALIAS = "APP";
    private static final String ENDPOINT_ID = "app";
    private static final String ENDPOINT_FILE = "endpoints/" + ENDPOINT_ID + ".yaml";
    private static final String GATEWAY_FILE = "gateway.yaml";
    private static final String ANCHOR = "api";
    private static final String ROUTE_ID = "proxied";
    private static final String ROUTE_COOKIES_POINTER = "/endpoint/routes/0/cookies";
    private static final String ENDPOINT_COOKIES_POINTER = "/endpoint/cookies";
    private static final String GATEWAY_COOKIES_POINTER = "/cookies";
    private static final String ANCHOR_COOKIES_POINTER = "/anchors/" + ANCHOR + "/cookies";

    private final ConfigValidator validator = new ConfigValidator();

    // --- fixture helpers -------------------------------------------------

    private static String cookieName() {
        return Generators.letterStrings(3, 12).next();
    }

    private static CookiesConfig named(String... names) {
        return new CookiesConfig(new CookieAllow.Named(List.of(names)), null, null);
    }

    private static CookiesConfig all() {
        return new CookiesConfig(CookieAllow.ALL, null, null);
    }

    private static ResolvedTopology topology() {
        return new ResolvedTopology(Map.of(ALIAS,
                new ResolvedUpstream("https", ALIAS.toLowerCase(Locale.ROOT) + ".internal", 443, "")));
    }

    private static RouteConfig.RouteConfigBuilder route() {
        return RouteConfig.builder().id(ROUTE_ID)
                .match(MatchConfig.builder().pathPrefix("/" + ANCHOR + "/" + ROUTE_ID).methods(List.of(HttpMethod.GET))
                        .build());
    }

    private static EndpointConfig.EndpointConfigBuilder endpoint(RouteConfig route) {
        return EndpointConfig.builder().id(ENDPOINT_ID).enabled(true).baseUrl(ALIAS).anchor(ANCHOR)
                .auth(AuthConfig.builder().require(Require.NONE).build()).routes(List.of(route));
    }

    private static AnchorConfig.AnchorConfigBuilder anchor() {
        return AnchorConfig.builder().name(ANCHOR).pathPrefix("/" + ANCHOR).type(AnchorType.PROXY)
                .access(AccessLevel.PUBLIC);
    }

    private static GatewayConfig.GatewayConfigBuilder gateway(AnchorConfig anchor) {
        return GatewayConfig.builder().version(1).anchors(Map.of(ANCHOR, anchor));
    }

    private List<ConfigError> validate(GatewayConfig gateway, EndpointConfig endpoint) {
        return validator.validate(gateway, List.of(endpoint), topology());
    }

    /** Validates one cookies block declared on the route, under a plain anchor and gateway. */
    private List<ConfigError> validateRouteCookies(CookiesConfig cookies) {
        return validate(gateway(anchor().build()).build(), endpoint(route().cookies(cookies).build()).build());
    }

    private static ConfigError singleRefusalAt(List<ConfigError> errors, String file, String pointer) {
        List<ConfigError> refusals = errors.stream()
                .filter(error -> file.equals(error.file()) && pointer.equals(error.pointer())).toList();
        assertEquals(1, refusals.size(),
                () -> "expected exactly one refusal at " + file + pointer + ", got: " + errors);
        return refusals.getFirst();
    }

    private static void assertBootsClean(List<ConfigError> errors) {
        assertTrue(errors.isEmpty(), () -> "the configuration must boot clean, got: " + errors);
    }

    @Test
    @DisplayName("Should boot clean with no cookies block declared anywhere (baseline)")
    void shouldBootCleanWithoutCookies() {
        assertBootsClean(validate(gateway(anchor().build()).build(), endpoint(route().build()).build()));
    }

    @Nested
    @DisplayName("(a) allow: all is refused at the gateway scope")
    class GatewayAllowAll {

        @Test
        @DisplayName("Should refuse a gateway-scope allow: all")
        void shouldRefuseGatewayAllowAll() {
            List<ConfigError> errors = validate(gateway(anchor().build()).cookies(all()).build(),
                    endpoint(route().build()).build());

            ConfigError refusal = singleRefusalAt(errors, GATEWAY_FILE, GATEWAY_COOKIES_POINTER + "/allow");
            assertAll("the refusal names the posture and the remedy",
                    () -> assertEquals(1, errors.size(), () -> "exactly one violation, got: " + errors),
                    () -> assertTrue(refusal.message().contains("allow: all"), refusal::message));
        }

        @Test
        @DisplayName("Should accept allow: all at the anchor, endpoint and route scopes (matched control)")
        void shouldAcceptAllBelowTheGateway() {
            assertAll("allow: all below the gateway",
                    () -> assertBootsClean(validate(gateway(anchor().cookies(all()).build()).build(),
                            endpoint(route().build()).build())),
                    () -> assertBootsClean(validate(gateway(anchor().build()).build(),
                            endpoint(route().build()).cookies(all()).build())),
                    () -> assertBootsClean(validateRouteCookies(all())),
                    () -> assertBootsClean(validate(gateway(anchor().build()).cookies(named(cookieName())).build(),
                            endpoint(route().build()).build())));
        }
    }

    @Nested
    @DisplayName("(b) a listed name must be an RFC 6265 cookie name")
    class CookieNames {

        @ParameterizedTest(name = "name ''{0}''")
        @ValueSource(strings = {"", "a b", "a;b", "a=b", "a,b", "tab\tname", "line\nbreak"})
        @DisplayName("Should refuse a listed name that is not a token")
        void shouldRefuseNonTokenName(String name) {
            List<ConfigError> errors = validateRouteCookies(named(name));

            ConfigError refusal = singleRefusalAt(errors, ENDPOINT_FILE, ROUTE_COOKIES_POINTER + "/allow");
            assertAll("the refusal names the route and is CWE-117 safe",
                    () -> assertTrue(refusal.message().contains("route '" + ROUTE_ID + "'"), refusal::message),
                    () -> assertTrue(refusal.message().contains("RFC 6265"), refusal::message),
                    () -> assertTrue(refusal.message().chars().noneMatch(c -> c == '\n' || c == '\t'),
                            "a control character is escaped, never echoed: " + refusal.message()));
        }

        @Test
        @DisplayName("Should accept token names, prefixed ones included (matched control)")
        void shouldAcceptTokenNames() {
            assertBootsClean(validateRouteCookies(named(cookieName(), "__Secure-" + cookieName(), "JSESSIONID",
                    "theme_v2.1")));
        }

        @Test
        @DisplayName("Should check the names of the block at every scope it is declarable at")
        void shouldCheckEveryScope() {
            CookiesConfig bad = named("a b");

            List<ConfigError> errors = validate(gateway(anchor().cookies(bad).build()).cookies(bad).build(),
                    endpoint(route().cookies(bad).build()).cookies(bad).build());

            assertAll("one refusal per declaring scope",
                    () -> singleRefusalAt(errors, GATEWAY_FILE, GATEWAY_COOKIES_POINTER + "/allow"),
                    () -> singleRefusalAt(errors, GATEWAY_FILE, ANCHOR_COOKIES_POINTER + "/allow"),
                    () -> singleRefusalAt(errors, ENDPOINT_FILE, ENDPOINT_COOKIES_POINTER + "/allow"),
                    () -> singleRefusalAt(errors, ENDPOINT_FILE, ROUTE_COOKIES_POINTER + "/allow"),
                    () -> assertEquals(4, errors.size(), () -> "exactly four violations, got: " + errors));
        }
    }

    @Nested
    @DisplayName("(c) a listed name must not be one of the gateway's own cookies")
    class GatewayCookieNotListed {

        @Test
        @DisplayName("Should refuse a listed name under the reserved prefix, whatever the letter case of __Host-")
        void shouldRefuseReservedPrefixName() {
            String listed = "__host-sheriff-" + cookieName();
            List<ConfigError> errors = validateRouteCookies(named(listed));

            ConfigError refusal = singleRefusalAt(errors, ENDPOINT_FILE, ROUTE_COOKIES_POINTER + "/allow");
            assertAll("the refusal names the listed gateway cookie",
                    () -> assertEquals(1, errors.size(), () -> "exactly one violation, got: " + errors),
                    () -> assertTrue(refusal.message().contains("one of the gateway's own cookies")
                            && refusal.message().contains(listed), refusal::message));
        }

        @Test
        @DisplayName("Should refuse the default session cookie name on a gateway without a session runtime")
        void shouldRefuseDefaultSessionCookieName() {
            List<ConfigError> errors = validateRouteCookies(named(SessionCookieCodec.DEFAULT_COOKIE_NAME));

            ConfigError refusal = singleRefusalAt(errors, ENDPOINT_FILE, ROUTE_COOKIES_POINTER + "/allow");
            assertTrue(refusal.message().contains(SessionCookieCodec.DEFAULT_COOKIE_NAME), refusal::message);
        }

        @Test
        @DisplayName("Should refuse a listed name at the gateway scope too")
        void shouldRefuseAtTheGatewayScope() {
            List<ConfigError> errors = validate(
                    gateway(anchor().build()).cookies(named(GatewayCookies.RESERVED_PREFIX + cookieName())).build(),
                    endpoint(route().build()).build());

            singleRefusalAt(errors, GATEWAY_FILE, GATEWAY_COOKIES_POINTER + "/allow");
        }

        @Test
        @DisplayName("Should accept a non-gateway name sharing a cookie prefix (matched control)")
        void shouldAcceptNonGatewayNames() {
            assertBootsClean(validateRouteCookies(named("__Secure-" + cookieName(), "__Host-" + cookieName(),
                    "JSESSIONID")));
        }
    }

    @Nested
    @DisplayName("(d) same_origin_check and additional_origins are refused under allow: none")
    class InertKeysOnNone {

        @Test
        @DisplayName("Should refuse same_origin_check on an allow: none block")
        void shouldRefuseSameOriginCheckOnNone() {
            List<ConfigError> errors = validateRouteCookies(new CookiesConfig(CookieAllow.NONE, true, null));

            ConfigError refusal = singleRefusalAt(errors, ENDPOINT_FILE, ROUTE_COOKIES_POINTER + "/same_origin_check");
            assertAll(
                    () -> assertEquals(1, errors.size(), () -> "exactly one violation, got: " + errors),
                    () -> assertTrue(refusal.message().contains("cookies.same_origin_check"), refusal::message));
        }

        @Test
        @DisplayName("Should refuse additional_origins on an allow: none block, a declared empty list included")
        void shouldRefuseAdditionalOriginsOnNone() {
            List<ConfigError> declared = validateRouteCookies(
                    new CookiesConfig(CookieAllow.NONE, null, List.of("https://app.example.com")));
            List<ConfigError> empty = validateRouteCookies(new CookiesConfig(CookieAllow.NONE, null, List.of()));

            assertAll("an inert key is refused whatever it holds",
                    () -> singleRefusalAt(declared, ENDPOINT_FILE, ROUTE_COOKIES_POINTER + "/additional_origins"),
                    () -> singleRefusalAt(empty, ENDPOINT_FILE, ROUTE_COOKIES_POINTER + "/additional_origins"),
                    () -> assertEquals(1, empty.size(), () -> "exactly one violation, got: " + empty));
        }

        @Test
        @DisplayName("Should accept both keys on a cookie-forwarding block and a bare allow: none (matched control)")
        void shouldAcceptKeysOnForwardingBlock() {
            assertAll(
                    () -> assertBootsClean(validateRouteCookies(new CookiesConfig(
                            new CookieAllow.Named(List.of(cookieName())), false, List.of("https://app.example.com")))),
                    () -> assertBootsClean(validateRouteCookies(CookiesConfig.NONE)));
        }
    }

    @Nested
    @DisplayName("(e) every additional_origins entry must be a serialized origin")
    class AdditionalOrigins {

        @ParameterizedTest(name = "origin ''{0}''")
        @ValueSource(strings = {"https://app.example.com/", "https://app.example.com/path", "ftp://app.example.com",
                "https://user@app.example.com", "https://app.example.com?q=1", "https://app.example.com#frag",
                "https://app.example.com:", "https://app.example.com:0", "https://app.example.com:65536",
                "app.example.com", "https://", "*", "not a url"})
        @DisplayName("Should refuse an entry that is not a serialized origin")
        void shouldRefuseNonOrigin(String origin) {
            List<ConfigError> errors = validateRouteCookies(
                    new CookiesConfig(CookieAllow.ALL, null, List.of("https://ok.example.com", origin)));

            ConfigError refusal = singleRefusalAt(errors, ENDPOINT_FILE, ROUTE_COOKIES_POINTER + "/additional_origins");
            assertTrue(refusal.message().contains("not a serialized origin"), refusal::message);
        }

        @ParameterizedTest(name = "origin ''{0}''")
        @ValueSource(strings = {"https://app.example.com", "http://localhost:8080", "https://[::1]:8443",
                "HTTPS://App.Example.com:443"})
        @DisplayName("Should accept a serialized origin, in any letter case (matched control)")
        void shouldAcceptSerializedOrigin(String origin) {
            assertBootsClean(validateRouteCookies(new CookiesConfig(CookieAllow.ALL, null, List.of(origin))));
        }
    }

    @Nested
    @DisplayName("(f) a route-scope cookies block is refused on an asset or redirect route")
    class TerminalRoute {

        @Test
        @DisplayName("Should refuse a cookies block on a redirect route")
        void shouldRefuseOnRedirectRoute() {
            RouteConfig redirect = route().redirect(RedirectConfig.builder().location("/home").status(302).build())
                    .cookies(named(cookieName())).build();

            List<ConfigError> errors = validate(gateway(anchor().build()).build(), endpoint(redirect).build());

            ConfigError refusal = singleRefusalAt(errors, ENDPOINT_FILE, ROUTE_COOKIES_POINTER);
            assertAll(
                    () -> assertEquals(1, errors.size(), () -> "exactly one violation, got: " + errors),
                    () -> assertTrue(refusal.message().contains("route '" + ROUTE_ID + "'")
                            && refusal.message().contains("no upstream"), refusal::message));
        }

        @Test
        @DisplayName("Should accept a broader cookies block over a redirect route (matched control)")
        void shouldAcceptBroaderBlockOverRedirectRoute() {
            RouteConfig redirect = route().redirect(RedirectConfig.builder().location("/home").status(302).build())
                    .build();

            assertBootsClean(validate(gateway(anchor().cookies(all()).build()).build(),
                    endpoint(redirect).cookies(named(cookieName())).build()));
        }
    }

    /**
     * A WebSocket route carries no per-route same-origin check; its upgrade is gated by
     * {@code websocket.allowed_origins} instead, which enforces nothing while empty.
     */
    @Nested
    @DisplayName("(g) a cookie-forwarding WebSocket route must declare websocket.allowed_origins")
    class WebSocketCookieOrigins {

        private static final String WEBSOCKET_POINTER = "/endpoint/routes/0/websocket";

        private static RouteConfig.RouteConfigBuilder websocketRoute(@Nullable WebSocketConfig websocket) {
            return route().protocol(Protocol.WEBSOCKET).websocket(websocket);
        }

        private static WebSocketConfig allowedOrigins(String... origins) {
            return WebSocketConfig.builder().allowedOrigins(List.of(origins)).build();
        }

        @Test
        @DisplayName("Should refuse a cookie-forwarding WebSocket route without a websocket block")
        void shouldRefuseWithoutWebSocketBlock() {
            List<ConfigError> errors = validate(gateway(anchor().build()).build(),
                    endpoint(websocketRoute(null).cookies(all()).build()).build());

            ConfigError refusal = singleRefusalAt(errors, ENDPOINT_FILE, WEBSOCKET_POINTER);
            assertAll("the refusal names the route and the missing allowlist",
                    () -> assertEquals(1, errors.size(), () -> "exactly one violation, got: " + errors),
                    () -> assertTrue(refusal.message().contains("websocket route '" + ROUTE_ID + "'"),
                            refusal::message),
                    () -> assertTrue(refusal.message().contains("websocket.allowed_origins"), refusal::message));
        }

        @Test
        @DisplayName("Should refuse a cookie-forwarding WebSocket route with an empty allowed_origins list")
        void shouldRefuseWithEmptyAllowedOrigins() {
            List<ConfigError> errors = validate(gateway(anchor().build()).build(),
                    endpoint(websocketRoute(allowedOrigins()).cookies(named(cookieName())).build()).build());

            singleRefusalAt(errors, ENDPOINT_FILE, WEBSOCKET_POINTER);
        }

        @Test
        @DisplayName("Should judge the effective block, so a block inherited from the endpoint is refused too")
        void shouldJudgeTheInheritedBlock() {
            List<ConfigError> errors = validate(gateway(anchor().build()).build(),
                    endpoint(websocketRoute(null).build()).cookies(all()).build());

            singleRefusalAt(errors, ENDPOINT_FILE, WEBSOCKET_POINTER);
        }

        @Test
        @DisplayName("Should accept a cookie-forwarding WebSocket route declaring its allowed origins (matched control)")
        void shouldAcceptWithAllowedOrigins() {
            assertBootsClean(validate(gateway(anchor().build()).build(),
                    endpoint(websocketRoute(allowedOrigins("https://app.example.com")).cookies(all()).build())
                            .build()));
        }

        @Test
        @DisplayName("Should accept a WebSocket route forwarding no cookie without allowed origins (matched control)")
        void shouldAcceptWithoutCookies() {
            assertBootsClean(validate(gateway(anchor().build()).build(), endpoint(websocketRoute(null).build()).build()));
        }
    }
}
