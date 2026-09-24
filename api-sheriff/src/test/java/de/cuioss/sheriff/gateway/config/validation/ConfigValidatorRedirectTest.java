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
package de.cuioss.sheriff.gateway.config.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;


import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AnchorType;
import de.cuioss.sheriff.gateway.config.model.AssetConfig;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.IssuerConfig;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.RedirectConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.TokenValidationConfig;
import de.cuioss.sheriff.gateway.config.model.UpstreamConfig;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the {@link ConfigValidator} redirect rules (ADR-0014 Amendment A1): the open-redirect
 * review of {@code location} with and without {@code allow_external}, the refusal of
 * {@code keep_query} together with {@code allow_external}, terminal-action exclusivity,
 * the http-only protocol restriction, the anchor matrix admitting a redirect under every anchor type,
 * and the boot WARN emitted when a route opts into an external target.
 * <p>
 * The refused spellings are literals on purpose: each one is a specific shape a browser or an
 * intermediary resolves to another origin, so the exact string <em>is</em> the contract under test.
 */
@EnableGeneratorController
@EnableTestLogger
@DisplayName("ConfigValidator — redirect terminal action")
class ConfigValidatorRedirectTest {

    private static final String ROUTES_POINTER = "/endpoint/routes";

    private final ConfigValidator validator = new ConfigValidator();

    private static String routeId() {
        return Generators.letterStrings(4, 10).next().toLowerCase(Locale.ROOT);
    }

    private static String externalHost() {
        return Generators.letterStrings(3, 10).next().toLowerCase(Locale.ROOT) + ".example";
    }

    private static RedirectConfig redirect(String location, boolean keepQuery, boolean allowExternal) {
        return RedirectConfig.builder()
                .location(location)
                .status(302)
                .keepQuery(keepQuery)
                .allowExternal(allowExternal)
                .build();
    }

    private static RouteConfig.RouteConfigBuilder redirectRoute(String id, RedirectConfig redirect) {
        return RouteConfig.builder()
                .id(id)
                .match(MatchConfig.builder().pathPrefix("/" + id).build())
                .redirect(redirect);
    }

    private static EndpointConfig endpoint(@Nullable String anchor, RouteConfig route) {
        return EndpointConfig.builder()
                .id("redirects")
                .enabled(true)
                .anchor(anchor)
                .auth(new AuthConfig(Require.NONE, null, null))
                .routes(List.of(route))
                .build();
    }

    private List<ConfigError> validate(RouteConfig route) {
        return validator.validate(GatewayConfig.builder().version(1).build(), List.of(endpoint(null, route)),
                new ResolvedTopology(Map.of()));
    }

    private static void assertRefused(List<ConfigError> errors, String messageContains) {
        assertTrue(errors.stream()
                        .anyMatch(error -> ROUTES_POINTER.equals(error.pointer())
                                && error.message().contains(messageContains)),
                () -> "expected a routes error containing '" + messageContains + "', but got: " + errors);
    }

    @Nested
    @DisplayName("Open-redirect review without allow_external")
    class GatewayPathReview {

        /**
         * Every location spelling the review must refuse, in two groups that share one body.
         * <p>
         * The first group is the single-decoding set: a scheme-relative prefix, a backslash in either
         * spelling, a percent-encoded separator, a dot segment, a foreign or non-HTTP absolute URI, a
         * scheme-less host, the empty value, and a path carrying whitespace.
         * <p>
         * The second group is the class the substring tests this review used to carry could not see,
         * now refused because the path portion is handed to {@code LocationPathReview} and through it
         * to the {@code cui-http} {@code URL_PATH} pipeline. {@code %252f} is not the {@code %2f} the
         * encoded-separator test looks for and {@code %252e} is not the {@code %2e} the dot-segment
         * test recognizes, so every value in that group used to be admitted at boot.
         * <p>
         * They are one test rather than two because the assertion is identical — the same refusal,
         * about the same production path — and a failure is identified by the offending value in the
         * case name, never by which group's method held it.
         */
        @ParameterizedTest(name = "refuses {0}")
        @ValueSource(strings = {
                // Single-decoding spellings
                "//evil.example",
                "//evil.example/path",
                "/\\evil.example",
                "\\\\evil.example",
                "/%2F%2Fevil.example",
                "/%2f%2fevil.example",
                "/%5cevil.example",
                "/%5Cevil.example",
                "/a/../b",
                "/a/./b",
                "/a/..",
                "/%2e%2e/b",
                "/%2E/b",
                "https://evil.example",
                "http://evil.example/path",
                "evil.example",
                "javascript:alert(1)",
                "",
                "/a b",
                "/a\tb",
                // Double-encoded or malformed escapes the single-decoding spellings above miss
                "/%252F%252Fevil.example",
                "/%252f%252fevil.example",
                "/a/%252f..%252fadmin",
                "/a/%252e%252e/b",
                "/a%00b",
                "/a%c0%afb"
        })
        @DisplayName("Should refuse every location spelling that can leave the gateway origin")
        void shouldRefuseOpenRedirectSpellings(String location) {
            String id = routeId();

            List<ConfigError> errors = validate(redirectRoute(id, redirect(location, false, false)).build());

            assertRefused(errors, "route '%s' redirect location".formatted(id));
        }

        @Test
        @DisplayName("Should refuse a location carrying CR/LF and never echo the raw control characters")
        void shouldRefuseHeaderInjectionWithoutForgingLogLines() {
            String id = routeId();

            List<ConfigError> errors = validate(
                    redirectRoute(id, redirect("/home\r\nSet-Cookie: stolen=1", false, false)).build());

            assertRefused(errors, "printable ASCII");
            assertTrue(errors.stream().noneMatch(error -> error.message().contains("\r")
                            || error.message().contains("\n")),
                    () -> "a refusal must render control characters escaped: " + errors);
        }

        @Test
        @DisplayName("Should refuse a fragment when keep_query is on, and admit it when keep_query is off")
        void shouldRefuseFragmentOnlyWithKeepQuery() {
            String location = "/docs#" + Generators.letterStrings(2, 8).next();

            List<ConfigError> withKeepQuery = validate(redirectRoute(routeId(), redirect(location, true, false)).build());
            List<ConfigError> withoutKeepQuery =
                    validate(redirectRoute(routeId(), redirect(location, false, false)).build());

            assertAll(
                    () -> assertRefused(withKeepQuery, "'#' fragment"),
                    () -> assertTrue(withoutKeepQuery.isEmpty(),
                            () -> "a fragment without keep_query is harmless, got: " + withoutKeepQuery));
        }

        @ParameterizedTest(name = "admits {0}")
        @ValueSource(strings = {"/", "/new-home", "/a/b/c", "/a/b?x=1&y=2", "/a...b/..c/.d", "/search?q=%2e%2e"})
        @DisplayName("Should admit a single-slash gateway path free of every refused shape")
        void shouldAdmitGatewayPaths(String location) {
            List<ConfigError> errors = validate(redirectRoute(routeId(), redirect(location, true, false)).build());

            assertTrue(errors.isEmpty(), () -> "gateway path '" + location + "' should be admitted, got: " + errors);
        }

        @Test
        @DisplayName("Should refuse a gateway path above the review's length cap")
        void shouldRefuseOverlongGatewayPath() {
            String id = routeId();

            List<ConfigError> errors =
                    validate(redirectRoute(id, redirect("/" + "a".repeat(1024), false, false)).build());

            assertRefused(errors, "route '%s' redirect location".formatted(id));
        }
    }

    @Nested
    @DisplayName("Open-redirect review with allow_external")
    class ExternalReview {

        @Test
        @DisplayName("Should admit an absolute http or https URI with a host and no user-info")
        void shouldAdmitAbsoluteHttpUris() {
            String https = "https://" + externalHost() + "/landing?from=gateway";
            String http = "http://" + externalHost() + ":8080/";

            List<ConfigError> httpsErrors = validate(redirectRoute(routeId(), redirect(https, false, true)).build());
            List<ConfigError> httpErrors = validate(redirectRoute(routeId(), redirect(http, false, true)).build());

            assertAll(
                    () -> assertTrue(httpsErrors.isEmpty(), () -> "https target should be admitted: " + httpsErrors),
                    () -> assertTrue(httpErrors.isEmpty(), () -> "http target should be admitted: " + httpErrors));
        }

        @Test
        @DisplayName("Should refuse keep_query together with allow_external, naming both keys and never the location")
        void shouldRefuseQueryHandOffToForeignOrigin() {
            String id = routeId();
            String location = "https://" + externalHost() + "/cb";

            List<ConfigError> errors = validate(redirectRoute(id, redirect(location, true, true)).build());

            // The location itself is a reviewed, fixed value; the QUERY is attacker-supplied, so the
            // pair is what leaks (?code=...&state=... would land in the partner's access log).
            assertAll(
                    () -> assertRefused(errors,
                            "route '%s' declares both keep_query and allow_external".formatted(id)),
                    () -> assertTrue(errors.stream().noneMatch(error -> error.message().contains(location)),
                            () -> "the pair refusal names the route and the keys, never the target: " + errors));
        }

        @Test
        @DisplayName("Should admit either flag on its own — only the combination is refused")
        void shouldAdmitEitherFlagAlone() {
            List<ConfigError> keepQueryOnly =
                    validate(redirectRoute(routeId(), redirect("/moved", true, false)).build());
            List<ConfigError> allowExternalOnly = validate(
                    redirectRoute(routeId(), redirect("https://" + externalHost() + "/cb", false, true)).build());

            assertAll(
                    () -> assertTrue(keepQueryOnly.isEmpty(),
                            () -> "keep_query alone keeps the query same-origin: " + keepQueryOnly),
                    () -> assertTrue(allowExternalOnly.isEmpty(),
                            () -> "allow_external alone carries no request data: " + allowExternalOnly));
        }

        @ParameterizedTest(name = "refuses {0}")
        @ValueSource(strings = {
                "javascript:alert(1)",
                "data:text/html,hi",
                "ftp://files.example/",
                "https://user:secret@evil.example/",
                "https://user@evil.example",
                "https:///no-host",
                "https:opaque",
                "//evil.example",
                "/\\evil.example",
                "https://evil.example/\\path"
        })
        @DisplayName("Should keep refusing non-http schemes, user-info, missing hosts and gateway-path hazards")
        void shouldRefuseUnsafeExternalTargets(String location) {
            String id = routeId();

            List<ConfigError> errors = validate(redirectRoute(id, redirect(location, false, true)).build());

            assertRefused(errors, "route '%s' redirect location".formatted(id));
        }

        @Test
        @DisplayName("Should warn once per opted-in route, naming the route id and never the location")
        void shouldWarnNamingRouteOnly() {
            String id = routeId();
            String location = "https://" + externalHost() + "/";

            validate(redirectRoute(id, redirect(location, false, true)).build());

            LogAsserts.assertSingleLogMessagePresent(TestLogLevel.WARN,
                    ConfigLogMessages.WARN.REDIRECT_EXTERNAL_TARGET_ALLOWED.format(id));
        }

        @Test
        @DisplayName("Should not warn for a redirect that does not opt into external targets")
        void shouldNotWarnWithoutOptIn() {
            validate(redirectRoute(routeId(), redirect("/new-home", false, false)).build());

            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, ConfigValidator.class);
        }
    }

    @Nested
    @DisplayName("Terminal-action exclusivity and protocol")
    class ExclusivityAndProtocol {

        @Test
        @DisplayName("Should refuse a redirect declared together with an asset action")
        void shouldRefuseRedirectWithAsset() {
            String id = routeId();
            RouteConfig route = redirectRoute(id, redirect("/new-home", false, false))
                    .asset(AssetConfig.builder().source(AssetConfig.Source.DIRECTORY).directory("/srv/www").build())
                    .build();

            List<ConfigError> errors = validate(route);

            assertRefused(errors, "route '%s' declares both a redirect and an asset terminal action".formatted(id));
        }

        @Test
        @DisplayName("Should refuse a redirect declared together with an upstream block")
        void shouldRefuseRedirectWithUpstream() {
            String id = routeId();
            RouteConfig route = redirectRoute(id, redirect("/new-home", false, false))
                    .upstream(UpstreamConfig.builder().path("/v2").build())
                    .build();

            List<ConfigError> errors = validate(route);

            assertRefused(errors, "route '%s' declares both a redirect terminal action and an upstream block"
                    .formatted(id));
        }

        @ParameterizedTest(name = "refuses protocol {0}")
        @EnumSource(value = Protocol.class, mode = EnumSource.Mode.EXCLUDE, names = "HTTP")
        @DisplayName("Should refuse a redirect on every protocol other than http")
        void shouldRefuseRedirectOnNonHttpProtocol(Protocol protocol) {
            String id = routeId();
            RouteConfig route = redirectRoute(id, redirect("/new-home", false, false)).protocol(protocol).build();

            List<ConfigError> errors = validate(route);

            assertRefused(errors, "its protocol is '%s'".formatted(protocol.name().toLowerCase(Locale.ROOT)));
        }

        @Test
        @DisplayName("Should admit a redirect on an explicitly declared http route")
        void shouldAdmitRedirectOnHttpProtocol() {
            RouteConfig route = redirectRoute(routeId(), redirect("/new-home", false, false))
                    .protocol(Protocol.HTTP).build();

            List<ConfigError> errors = validate(route);

            assertTrue(errors.isEmpty(), () -> "an http redirect route should be admitted, got: " + errors);
        }
    }

    @Nested
    @DisplayName("Anchor matrix")
    class AnchorMatrix {

        /**
         * Validates {@code route} under a {@code site} anchor of {@code type}, built so the ADR-0013
         * access→auth matrix admits the <em>anchor</em> — otherwise a {@code bff} case would fail on
         * the matrix rather than on the redirect rule under test, and the matrix rules are covered by
         * {@code ConfigValidatorTest}, not here.
         * <p>
         * The discriminator is the validator's own rule — {@code type: bff} implies
         * {@code access: authenticated} with a backed non-{@code none} floor — rather than a list of
         * the types that need one. A {@code bff} anchor therefore gets a bearer floor, the
         * {@code token_validation} issuer that backs it, and an endpoint that does not weaken the
         * floor; every other type stays {@code access: public} with no auth block. A new
         * {@link AnchorType} constant lands in the public arm and fails loudly here if that is wrong,
         * instead of silently skipping the matrix.
         */
        private List<ConfigError> validateAnchored(AnchorType type, RouteConfig route) {
            boolean authenticated = type == AnchorType.BFF;
            AnchorConfig anchor = AnchorConfig.builder()
                    .name("site")
                    .pathPrefix("/site")
                    .type(type)
                    .access(authenticated ? AccessLevel.AUTHENTICATED : AccessLevel.PUBLIC)
                    .auth(authenticated ? new AuthConfig(Require.BEARER, null, null) : null)
                    .build();
            GatewayConfig gateway = GatewayConfig.builder()
                    .version(1)
                    .anchors(Map.of("site", anchor))
                    .tokenValidation(authenticated ? new TokenValidationConfig(List.of(
                            IssuerConfig.builder().name("idp").issuer("https://idp.example/").build())) : null)
                    .build();
            EndpointConfig anchoredEndpoint = EndpointConfig.builder()
                    .id("redirects")
                    .enabled(true)
                    .anchor("site")
                    .auth(new AuthConfig(authenticated ? Require.BEARER : Require.NONE, null, null))
                    .routes(List.of(route))
                    .build();
            return validator.validate(gateway, List.of(anchoredEndpoint), new ResolvedTopology(Map.of()));
        }

        // Derived from AnchorType.values(), not listed: AnchorType declares PROXY, BFF and ASSET, and
        // a redirect is admitted under all three. Naming only two of them let a validator regression
        // that rejects a redirect under a valid BFF anchor pass this test unobserved.
        @ParameterizedTest(name = "admits a redirect under a {0} anchor")
        @EnumSource(AnchorType.class)
        @DisplayName("Should admit a redirect route under every anchor type")
        void shouldAdmitRedirectUnderAnchor(AnchorType type) {
            RouteConfig route = RouteConfig.builder()
                    .id(routeId())
                    .match(MatchConfig.builder().pathPrefix("/site/old").build())
                    .redirect(redirect("/site/new", false, false))
                    .build();

            List<ConfigError> errors = validateAnchored(type, route);

            assertTrue(errors.isEmpty(), () -> "a redirect is admitted under a " + type + " anchor, got: " + errors);
        }

        @Test
        @DisplayName("Should refuse an asset-anchor route declaring neither an asset nor a redirect action")
        void shouldRefuseAssetAnchorRouteWithoutAssetOrRedirect() {
            String id = routeId();
            RouteConfig route = RouteConfig.builder()
                    .id(id)
                    .match(MatchConfig.builder().pathPrefix("/site/old").build())
                    .build();

            List<ConfigError> errors = validateAnchored(AnchorType.ASSET, route);

            assertAll(
                    () -> assertRefused(errors, "no asset terminal action and no redirect terminal action"),
                    () -> assertFalse(errors.isEmpty(), "the missing terminal action must fail the boot"),
                    () -> assertEquals(1, errors.stream()
                                    .filter(error -> error.message().contains("route '%s'".formatted(id)))
                                    .count(),
                            () -> "exactly one terminal-action refusal is expected: " + errors));
        }
    }
}
