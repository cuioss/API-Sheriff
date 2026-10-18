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
import java.util.Set;

import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@code oidc.back_channel_origin} / {@code oidc.back_channel_allow_insecure_http} boot
 * refusals the {@link ConfigValidator} runs: a declared origin is held to a bare {@code http}/{@code https}
 * origin, an {@code http} origin is admitted only under the explicit cleartext opt-in, and the opt-in is
 * refused when there is nothing to opt in for.
 * <p>
 * Every case drives the full default validator, so a rule dropped from its registration fails here as
 * surely as a rule whose body is removed: each refusal case asserts exactly one refusal on the
 * back-channel pointers. Every refusal also asserts that its message does not carry the configured
 * value, because the value may carry a credential in its user-info. The hosts are generated and carry a
 * hyphen, so no value can be mistaken for a fragment of the fixed refusal text.
 */
@EnableGeneratorController
@DisplayName("ConfigValidator — oidc.back_channel_origin and its cleartext opt-in")
class ConfigValidatorBackChannelOriginTest {

    private static final String GATEWAY_FILE = "gateway.yaml";
    private static final String ORIGIN_POINTER = "/oidc/back_channel_origin";
    private static final String OPT_IN_POINTER = "/oidc/back_channel_allow_insecure_http";
    private static final Set<String> BACK_CHANNEL_POINTERS = Set.of(ORIGIN_POINTER, OPT_IN_POINTER);

    private final ConfigValidator validator = new ConfigValidator();

    /** A generated host carrying a hyphen, e.g. {@code abcd-efgh}, so it is never a word of the refusal text. */
    private static String host() {
        return letters() + "-" + letters();
    }

    private static String letters() {
        return Generators.letterStrings(4, 10).next().toLowerCase(Locale.ROOT);
    }

    private static int port() {
        return Generators.integers(1024, 65535).next();
    }

    private static OidcConfig oidc(@Nullable String origin, @Nullable Boolean allowInsecureHttp) {
        return OidcConfig.builder()
                .issuer("https://" + host() + "/realms/" + letters())
                .redirectUri("https://gw.example.com/bff/callback")
                .backChannelOrigin(origin)
                .backChannelAllowInsecureHttp(allowInsecureHttp)
                .build();
    }

    private List<ConfigError> backChannelErrors(OidcConfig oidc) {
        GatewayConfig gateway = GatewayConfig.builder().version(1).oidc(oidc).build();
        return validator.validate(gateway, List.of(), new ResolvedTopology(Map.of())).stream()
                .filter(error -> BACK_CHANNEL_POINTERS.contains(error.pointer()))
                .toList();
    }

    /**
     * Asserts exactly one back-channel refusal, on {@code pointer} in {@code gateway.yaml}, whose fixed
     * text names the refused key and never the configured value.
     */
    private void assertSingleRefusal(@Nullable String origin, @Nullable Boolean allowInsecureHttp, String pointer,
            String expectedFragment) {
        List<ConfigError> errors = backChannelErrors(oidc(origin, allowInsecureHttp));

        assertEquals(1, errors.size(), () -> "expected exactly one back-channel refusal, got: " + errors);
        ConfigError refusal = errors.getFirst();
        assertAll("the refusal of back_channel_origin=" + origin + ", back_channel_allow_insecure_http="
                + allowInsecureHttp,
                () -> assertEquals(GATEWAY_FILE, refusal.file()),
                () -> assertEquals(pointer, refusal.pointer()),
                () -> assertTrue(refusal.message().contains(expectedFragment), refusal::message),
                () -> assertTrue(origin == null || !refusal.message().contains(origin),
                        () -> "the refusal must never echo the configured value: " + refusal.message()));
    }

    @Nested
    @DisplayName("accepted values")
    class Accepted {

        @Test
        @DisplayName("an absent key with no opt-in passes: the issuer's own origin is dialled")
        void acceptsAnAbsentKey() {
            assertEquals(List.of(), backChannelErrors(oidc(null, null)));
        }

        @Test
        @DisplayName("an https origin with a port passes")
        void acceptsAnHttpsOrigin() {
            assertEquals(List.of(), backChannelErrors(oidc("https://" + host() + ":" + port(), null)));
        }

        @Test
        @DisplayName("an https origin followed by a single trailing '/' passes")
        void acceptsAnHttpsOriginWithATrailingSlash() {
            assertEquals(List.of(), backChannelErrors(oidc("https://" + host() + ":" + port() + "/", null)));
        }

        @Test
        @DisplayName("an https origin with an explicit false opt-in passes")
        void acceptsAnHttpsOriginWithAFalseOptIn() {
            assertEquals(List.of(), backChannelErrors(oidc("https://" + host() + ":" + port(), false)));
        }

        @Test
        @DisplayName("an http origin passes under back_channel_allow_insecure_http: true")
        void acceptsAnHttpOriginUnderTheOptIn() {
            assertEquals(List.of(), backChannelErrors(oidc("http://" + host() + ":" + port(), true)));
        }
    }

    @Nested
    @DisplayName("refused shapes, each on /oidc/back_channel_origin")
    class RefusedShape {

        @Test
        @DisplayName("a blank value is refused as blank")
        void refusesABlankValue() {
            assertSingleRefusal("\t \t", null, ORIGIN_POINTER, "oidc.back_channel_origin is declared but blank");
        }

        @Test
        @DisplayName("a relative value is refused")
        void refusesARelativeValue() {
            assertSingleRefusal("/" + host(), null, ORIGIN_POINTER,
                    "oidc.back_channel_origin must be an absolute http or https URI naming a host");
        }

        @Test
        @DisplayName("an ftp origin is refused")
        void refusesAnFtpScheme() {
            assertSingleRefusal("ftp://" + host() + ":" + port(), null, ORIGIN_POINTER,
                    "oidc.back_channel_origin must be an absolute http or https URI naming a host");
        }

        @Test
        @DisplayName("an origin carrying user-info is refused without echoing the credential")
        void refusesUserInfo() {
            String user = letters();
            String password = host();
            String origin = "https://" + user + ":" + password + "@" + host() + ":" + port();

            assertSingleRefusal(origin, null, ORIGIN_POINTER, "oidc.back_channel_origin must not carry user-info");
            assertFalse(backChannelErrors(oidc(origin, null)).getFirst().message().contains(password),
                    "the password of the user-info must never reach the boot log");
        }

        @Test
        @DisplayName("an origin carrying a path is refused")
        void refusesAPath() {
            assertSingleRefusal("https://" + host() + ":" + port() + "/realms", null, ORIGIN_POINTER,
                    "oidc.back_channel_origin must not carry a path");
        }

        @Test
        @DisplayName("an origin carrying a query is refused")
        void refusesAQuery() {
            assertSingleRefusal("https://" + host() + ":" + port() + "?" + letters() + "=" + letters(), null,
                    ORIGIN_POINTER, "oidc.back_channel_origin must not carry a query");
        }

        @Test
        @DisplayName("an origin carrying a fragment is refused")
        void refusesAFragment() {
            assertSingleRefusal("https://" + host() + ":" + port() + "#" + letters(), null, ORIGIN_POINTER,
                    "oidc.back_channel_origin must not carry a fragment");
        }

        @Test
        @DisplayName("a malformed value is reported once, not judged against the opt-in as well")
        void reportsAShapeRefusalOnceUnderTheOptIn() {
            assertSingleRefusal("ftp://" + host() + ":" + port(), true, ORIGIN_POINTER,
                    "oidc.back_channel_origin must be an absolute http or https URI naming a host");
        }
    }

    @Nested
    @DisplayName("refused transport postures")
    class RefusedTransport {

        @Test
        @DisplayName("an http origin without the opt-in is refused on /oidc/back_channel_origin")
        void refusesAnHttpOriginWithoutTheOptIn() {
            assertSingleRefusal("http://" + host() + ":" + port(), null, ORIGIN_POINTER,
                    "oidc.back_channel_origin uses the http scheme but oidc.back_channel_allow_insecure_http is not true");
        }

        @Test
        @DisplayName("an http origin with an explicit false opt-in is refused on /oidc/back_channel_origin")
        void refusesAnHttpOriginWithAFalseOptIn() {
            assertSingleRefusal("http://" + host() + ":" + port(), false, ORIGIN_POINTER,
                    "oidc.back_channel_origin uses the http scheme but oidc.back_channel_allow_insecure_http is not true");
        }

        @Test
        @DisplayName("the opt-in with no origin is refused on /oidc/back_channel_allow_insecure_http")
        void refusesTheOptInWithoutAnOrigin() {
            assertSingleRefusal(null, true, OPT_IN_POINTER,
                    "oidc.back_channel_allow_insecure_http is true but no oidc.back_channel_origin is declared");
        }

        @Test
        @DisplayName("the opt-in with an https origin is refused on /oidc/back_channel_allow_insecure_http")
        void refusesTheOptInWithAnHttpsOrigin() {
            assertSingleRefusal("https://" + host() + ":" + port(), true, OPT_IN_POINTER,
                    "oidc.back_channel_allow_insecure_http is true but oidc.back_channel_origin uses the https scheme");
        }
    }
}
