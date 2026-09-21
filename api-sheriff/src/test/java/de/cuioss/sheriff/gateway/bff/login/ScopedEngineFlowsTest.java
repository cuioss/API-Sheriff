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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;


import de.cuioss.sheriff.token.client.auth.ClientSecretBasicAuth;
import de.cuioss.sheriff.token.client.config.ClientAuthMethod;
import de.cuioss.sheriff.token.client.config.ClientConfiguration;
import de.cuioss.sheriff.token.client.discovery.ProviderMetadata;
import de.cuioss.sheriff.token.client.flow.AuthorizationCodeFlow;
import de.cuioss.sheriff.token.client.flow.TokenEndpointClient;
import de.cuioss.sheriff.token.client.token.IdTokenValidationBridge;
import de.cuioss.sheriff.token.client.token.TokenValidationBridge;
import de.cuioss.sheriff.token.commons.error.TransportException;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.mockwebserver.EnableMockWebServer;
import de.cuioss.test.mockwebserver.URIBuilder;
import de.cuioss.test.mockwebserver.dispatcher.HttpMethodMapper;
import de.cuioss.test.mockwebserver.dispatcher.ModuleDispatcher;
import de.cuioss.test.mockwebserver.dispatcher.ModuleDispatcherElement;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import okio.ByteString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ScopedEngineFlows}: the per-scope-set seam over the unchanged engine carries the
 * requested scope set onto the authorization URL and onto the refresh grant, and reuses one login flow
 * per canonical scope set.
 * <p>
 * The login leg is local (the engine only renders a URL), so it runs against hand-built provider
 * metadata. The refresh leg posts a real grant to an in-process token endpoint that records the form
 * body and refuses the grant, which is all the {@code scope} assertion needs.
 */
@EnableGeneratorController
@EnableMockWebServer
@ModuleDispatcher
@DisplayName("ScopedEngineFlows — per-request scope on the login and refresh legs")
class ScopedEngineFlowsTest {

    private static final String ISSUER = "https://idp.example.com";
    private static final String REDIRECT_URI = "https://gw.example.com/auth/callback";
    private static final String TOKEN_PATH = "/token";
    private static final List<String> BASE_SCOPES = List.of("openid", "profile", "email");
    private static final List<String> SCOPED = List.of("openid", "profile", "email", "orders:read");

    private final TokenValidator tokenValidator = TokenValidator.builder()
            .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();
    /** Every scope list the configuration factory was asked for, in call order. */
    private final List<List<String>> factoryCalls = new CopyOnWriteArrayList<>();
    /** Every form body the stub token endpoint received. */
    private final List<String> tokenRequestBodies = new CopyOnWriteArrayList<>();

    /**
     * The stub token endpoint: records each form body and refuses the grant with {@code invalid_grant},
     * so the refresh leg's request is observable without minting a validatable token.
     *
     * @return the token-endpoint dispatcher
     */
    public ModuleDispatcherElement getModuleDispatcher() {
        return new ModuleDispatcherElement() {

            @Override
            public String getBaseUrl() {
                return TOKEN_PATH;
            }

            @Override
            public Optional<MockResponse> handlePost(RecordedRequest request) {
                ByteString body = request.getBody();
                tokenRequestBodies.add(body == null ? "" : body.utf8());
                return Optional.of(new MockResponse.Builder()
                        .code(400)
                        .addHeader("Content-Type", "application/json")
                        .body("{\"error\":\"invalid_grant\"}")
                        .build());
            }

            @Override
            public Set<HttpMethodMapper> supportedMethods() {
                return Set.of(HttpMethodMapper.POST);
            }
        };
    }

    private ClientConfiguration configuration(List<String> scopes) {
        factoryCalls.add(scopes);
        return ClientConfiguration.builder()
                .issuer(ISSUER).clientId("gateway-client").clientSecret("gateway-secret")
                .authMethod(ClientAuthMethod.CLIENT_SECRET_BASIC)
                .scopes(scopes).redirectUri(REDIRECT_URI)
                .allowInsecureHttp(true)
                .build();
    }

    private ScopedEngineFlows flows() {
        ClientConfiguration base = ClientConfiguration.builder()
                .issuer(ISSUER).clientId("gateway-client").clientSecret("gateway-secret")
                .authMethod(ClientAuthMethod.CLIENT_SECRET_BASIC)
                .scopes(BASE_SCOPES).redirectUri(REDIRECT_URI)
                .allowInsecureHttp(true)
                .build();
        return new ScopedEngineFlows(this::configuration, new TokenEndpointClient(base),
                new TokenValidationBridge(tokenValidator), new IdTokenValidationBridge(tokenValidator),
                new QueryResponseModeAuthorizationRequestBuilder(),
                new ClientSecretBasicAuth("gateway-client", "gateway-secret"));
    }

    private static ProviderMetadata metadata(String tokenEndpoint) {
        ProviderMetadata metadata = new ProviderMetadata();
        metadata.issuer = ISSUER;
        metadata.authorizationEndpoint = ISSUER + "/authorize";
        metadata.tokenEndpoint = tokenEndpoint;
        metadata.codeChallengeMethodsSupported = List.of(ProviderMetadata.CODE_CHALLENGE_METHOD_S256);
        return metadata;
    }

    /** Decodes a {@code application/x-www-form-urlencoded} string (a query or a form body) into its pairs. */
    private static Map<String, String> formPairs(String encoded) {
        Map<String, String> pairs = new LinkedHashMap<>();
        for (String pair : encoded.split("&")) {
            String[] nameValue = pair.split("=", 2);
            pairs.put(URLDecoder.decode(nameValue[0], StandardCharsets.UTF_8),
                    nameValue.length == 2 ? URLDecoder.decode(nameValue[1], StandardCharsets.UTF_8) : "");
        }
        return pairs;
    }

    private static Set<String> scopeSet(String scopeParameter) {
        return Arrays.stream(scopeParameter.split(" ")).collect(Collectors.toSet());
    }

    @Nested
    @DisplayName("Login leg")
    class LoginLeg {

        @Test
        @DisplayName("Should put exactly the requested scope set on the authorization URL, for two different sets")
        void shouldRequestExactlyTheRequestedSet() {
            ScopedEngineFlows flows = flows();
            ProviderMetadata metadata = metadata(ISSUER + TOKEN_PATH);

            Map<String, String> base = formPairs(URI.create(
                    flows.authorize(metadata, BASE_SCOPES).authorizationUrl()).getRawQuery());
            Map<String, String> scoped = formPairs(URI.create(
                    flows.authorize(metadata, List.of("orders:read", "email", "profile", "openid"))
                            .authorizationUrl()).getRawQuery());

            assertEquals(Set.copyOf(BASE_SCOPES), scopeSet(base.get("scope")),
                    "the base login requests the base set, no more and no less");
            assertEquals(Set.copyOf(SCOPED), scopeSet(scoped.get("scope")),
                    "the scoped login requests the scoped set, whatever order it was named in");
        }

        @Test
        @DisplayName("Should keep response_mode=query on every scoped authorization URL")
        void shouldKeepQueryResponseMode() {
            Map<String, String> query = formPairs(URI.create(
                    flows().authorize(metadata(ISSUER + TOKEN_PATH), SCOPED).authorizationUrl()).getRawQuery());

            assertEquals("query", query.get("response_mode"),
                    "a scoped flow is built with the gateway's query-mode request builder");
        }

        @Test
        @DisplayName("Should reuse one cached flow for the same scope set, whatever its order or duplicates")
        void shouldReuseCachedFlowForSameSet() {
            ScopedEngineFlows flows = flows();

            AuthorizationCodeFlow first = flows.authorizationFlow(List.of("openid", "profile"));
            AuthorizationCodeFlow reordered = flows.authorizationFlow(List.of("profile", "openid", "openid"));
            AuthorizationCodeFlow other = flows.authorizationFlow(List.of("openid"));

            assertSame(first, reordered, "the same canonical set shares one flow");
            assertNotSame(first, other, "a different set gets its own flow");
            assertEquals(List.of(List.of("openid", "profile"), List.of("openid")), factoryCalls,
                    "the configuration factory is asked once per canonical set, with the sorted list");
        }

        @Test
        @DisplayName("Should build the login configuration only once across repeated logins for one set")
        void shouldBuildConfigurationOncePerSet() {
            ScopedEngineFlows flows = flows();
            ProviderMetadata metadata = metadata(ISSUER + TOKEN_PATH);

            assertNotNull(flows.authorize(metadata, SCOPED));
            assertNotNull(flows.authorize(metadata, SCOPED));

            assertEquals(1, factoryCalls.size(), "a repeated login for one set reuses its cached flow");
        }
    }

    // The refresh-leg tests sit at the top level on purpose: the mock server resolves its
    // ModuleDispatcher from the test instance, and a @Nested instance does not declare one.

    @Test
    @DisplayName("Refresh leg: should post the passed scope set as the refresh grant's scope")
    void shouldRefreshWithPassedScopes(URIBuilder uriBuilder) {
        ScopedEngineFlows flows = flows();
        ProviderMetadata metadata = metadata(uriBuilder.addPathSegment("token").buildAsString());
        String refreshToken = Generators.letterStrings(16, 32).next();

        TransportException refused = assertThrows(TransportException.class,
                () -> flows.refresh(metadata, refreshToken, SCOPED),
                "the stub endpoint refuses the grant; only the request it received matters here");

        assertEquals(1, tokenRequestBodies.size(),
                "exactly one refresh grant reached the token endpoint (refusal: " + refused.getMessage() + ")");
        Map<String, String> form = formPairs(tokenRequestBodies.getFirst());
        assertEquals("refresh_token", form.get("grant_type"));
        assertEquals(refreshToken, form.get("refresh_token"));
        assertEquals(Set.copyOf(SCOPED), scopeSet(form.get("scope")),
                "the grant carries exactly the passed set, never the base configuration's scopes");
    }

    @Test
    @DisplayName("Refresh leg: should build a fresh configuration for every refresh — never cached")
    void shouldNotCacheRefreshFlows(URIBuilder uriBuilder) {
        ScopedEngineFlows flows = flows();
        ProviderMetadata metadata = metadata(uriBuilder.addPathSegment("token").buildAsString());
        Collection<String> scopes = List.of("openid");

        assertThrows(TransportException.class, () -> flows.refresh(metadata, "first-refresh-token", scopes));
        assertThrows(TransportException.class, () -> flows.refresh(metadata, "second-refresh-token", scopes));

        assertEquals(2, factoryCalls.size(), "each refresh asks the factory for its own configuration");
        assertEquals(2, tokenRequestBodies.size(), "each refresh posts its own grant");
    }

    @Nested
    @DisplayName("Canonical scope set")
    class Canonical {

        @Test
        @DisplayName("Should deduplicate and sort a scope set into its canonical list")
        void shouldCanonicalize() {
            assertEquals(List.of("email", "openid", "profile"),
                    ScopedEngineFlows.canonical(List.of("profile", "openid", "email", "openid")));
        }

        @Test
        @DisplayName("Should reject a null scope set and a null scope name")
        void shouldRejectNulls() {
            assertThrows(NullPointerException.class, () -> ScopedEngineFlows.canonical(null));
            assertThrows(NullPointerException.class,
                    () -> ScopedEngineFlows.canonical(Arrays.asList("openid", null)));
        }
    }
}
