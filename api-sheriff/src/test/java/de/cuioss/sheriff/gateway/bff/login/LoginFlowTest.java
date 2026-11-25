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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import de.cuioss.sheriff.gateway.bff.login.LoginFlow.AuthorizationInitiation;
import de.cuioss.sheriff.gateway.bff.login.LoginFlow.LoginRedirect;
import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.pending.InMemoryPendingAuthorizationStore;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.testsupport.SettableClock;
import de.cuioss.sheriff.token.client.auth.ClientAuthentication;
import de.cuioss.sheriff.token.client.auth.ClientSecretBasicAuth;
import de.cuioss.sheriff.token.client.config.ClientAuthMethod;
import de.cuioss.sheriff.token.client.config.ClientConfiguration;
import de.cuioss.sheriff.token.client.discovery.ProviderMetadata;
import de.cuioss.sheriff.token.client.flow.AuthorizationCodeFlow;
import de.cuioss.sheriff.token.client.flow.FlowContext;
import de.cuioss.sheriff.token.client.flow.RefreshFlow;
import de.cuioss.sheriff.token.client.flow.TokenEndpointClient;
import de.cuioss.sheriff.token.client.token.IdTokenValidationBridge;
import de.cuioss.sheriff.token.client.token.TokenValidationBridge;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.mockwebserver.EnableMockWebServer;
import de.cuioss.test.mockwebserver.URIBuilder;
import de.cuioss.test.mockwebserver.dispatcher.HttpMethodMapper;
import de.cuioss.test.mockwebserver.dispatcher.ModuleDispatcher;
import de.cuioss.test.mockwebserver.dispatcher.ModuleDispatcherElement;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import okio.ByteString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link LoginFlow}: the login-initiation orchestration — engine-driven authorization,
 * pending-record persistence, the browser-binding cookie, and the same-origin return-URL guard.
 * <p>
 * The engine authorization is driven through the {@link AuthorizationInitiation} seam with a
 * hand-built {@link AuthorizationCodeFlow.AuthorizationRedirect}, so the flow is exercised without a
 * live IdP or discovery round-trip. The composed cases at the top level bind the seam the way the
 * runtime does — to {@link ScopedEngineFlows#authorize} over the engine's own flow — against an
 * in-process pushed-authorization-request endpoint, so what the browser receives from a login is
 * pinned end to end: the redirect location, the {@code response_mode} that makes the callback a
 * top-level GET, and the {@code SameSite=Lax} binding cookie that is sent on exactly that navigation.
 * They sit at the top level because the mock server resolves its dispatcher from the test instance.
 */
@EnableGeneratorController
@EnableMockWebServer
@ModuleDispatcher
class LoginFlowTest {

    private static final Instant T0 = Instant.parse("2026-07-23T10:00:00Z");
    /** No time passes during a push unless a case moves it. */
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final String GATEWAY_ORIGIN = "https://gw.example.com";
    private static final String AUTHORIZATION_URL = "https://idp.example.com/authorize?client_id=api-sheriff";
    /** A configured {@code oidc.login.default_return_url} distinct from {@code /}. */
    private static final String CONFIGURED_DEFAULT = "/home";
    /** The scope set a caller names for the login; the flow forwards it to the seam unchanged. */
    private static final List<String> SCOPES = List.of("openid", "profile", "orders:read");
    /** The requester source the login is counted under. */
    private static final String QUOTA_KEY = "192.0.2.10";
    private static final String OTHER_QUOTA_KEY = "198.51.100.20";
    private static final String IDP_AUTHORIZATION_ENDPOINT = "https://idp.example.com/authorize";
    private static final String CLIENT_ID = "api-sheriff";
    private static final String REQUEST_URI = "urn:ietf:params:oauth:request_uri:login-flow-test";

    private InMemoryPendingAuthorizationStore pendingStore;
    private BindingCookieCodec bindingCodec;
    private FlowContext flowContext;
    private AtomicReference<Collection<String>> requestedScopes;
    private AuthorizationInitiation authorization;
    private LoginFlow loginFlow;

    @BeforeEach
    void setUp() {
        pendingStore = new InMemoryPendingAuthorizationStore(8);
        bindingCodec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
        flowContext = FlowContext.create(GATEWAY_ORIGIN + "/auth/callback");
        AuthorizationCodeFlow.AuthorizationRedirect redirect =
                new AuthorizationCodeFlow.AuthorizationRedirect(AUTHORIZATION_URL, flowContext);
        requestedScopes = new AtomicReference<>();
        authorization = scopes -> {
            requestedScopes.set(scopes);
            return redirect;
        };
        loginFlow = new LoginFlow(authorization, pendingStore, bindingCodec, GATEWAY_ORIGIN, CONFIGURED_DEFAULT, CLOCK);
    }

    private PendingAuthorizationRecord consumeBoundRecord(LoginRedirect result) {
        String cookieHeader = result.setCookieHeaders().getFirst().split(";", 2)[0];
        Optional<String> recordId = bindingCodec.readRecordId(cookieHeader);
        assertTrue(recordId.isPresent(), "the login sets a binding cookie carrying the record id");
        return pendingStore.consume(recordId.get(), T0).orElseThrow();
    }

    // ---------------------------------------------------------------------------------------------
    // Composed with the engine seam the runtime binds
    // ---------------------------------------------------------------------------------------------

    /** Every form body the pushed-authorization-request endpoint received. */
    private final List<String> pushedBodies = new CopyOnWriteArrayList<>();
    /** The answers the endpoint serves next; empty means the accepting default. */
    private final Queue<MockResponse> pushAnswers = new ConcurrentLinkedQueue<>();

    /**
     * The pushed-authorization-request endpoint: records each push and answers from the script, and
     * once the script is empty with {@code 201} and {@link #REQUEST_URI}.
     *
     * @return the endpoint dispatcher
     */
    public ModuleDispatcherElement getModuleDispatcher() {
        return new ModuleDispatcherElement() {

            @Override
            public String getBaseUrl() {
                return "/par";
            }

            @Override
            public Optional<MockResponse> handlePost(RecordedRequest request) {
                ByteString body = request.getBody();
                pushedBodies.add(body == null ? "" : body.utf8());
                MockResponse next = pushAnswers.poll();
                return Optional.of(next == null ? json(201,
                        "{\"request_uri\":\"" + REQUEST_URI + "\",\"expires_in\":60}") : next);
            }

            @Override
            public Set<HttpMethodMapper> supportedMethods() {
                return Set.of(HttpMethodMapper.POST);
            }
        };
    }

    @Test
    @DisplayName("Composed: the login redirect carries client_id and request_uri only, the push response_mode=query, and the binding cookie is Lax")
    void shouldRedirectWithThePushedLocationAndTheLaxBindingCookie(URIBuilder uriBuilder) {
        LoginFlow composed = composedLoginFlow(uriBuilder);

        LoginRedirect result = composed.initiate("/dashboard", List.of("profile", "orders:read", "openid", "openid"),
                QUOTA_KEY, T0);

        Map<String, String> location = pairs(result.authorizationUrl()
                .substring(IDP_AUTHORIZATION_ENDPOINT.length() + 1));
        assertEquals(1, pushedBodies.size(), "the login pushed its request once");
        Map<String, String> pushed = pairs(pushedBodies.getFirst());
        String setCookie = result.setCookieHeaders().getFirst();
        PendingAuthorizationRecord pending = consumeBoundRecord(result);
        assertAll("what the browser receives from a pushed login",
                () -> assertTrue(result.authorizationUrl().startsWith(IDP_AUTHORIZATION_ENDPOINT + "?"),
                        result.authorizationUrl()),
                () -> assertEquals(Map.of("client_id", CLIENT_ID, "request_uri", REQUEST_URI), location,
                        "the location carries client_id and request_uri and no authorization parameter"),
                () -> assertEquals("query", pushed.get("response_mode"),
                        "the callback is a top-level GET navigation"),
                () -> assertEquals("openid orders:read profile", pushed.get("scope"),
                        "the push carries the canonical scope set"),
                () -> assertEquals(pending.flowContext().state(), pushed.get("state"),
                        "the stored record is the transaction the push was made for"),
                () -> assertTrue(setCookie.startsWith(BindingCookieCodec.COOKIE_NAME + "=" + pending.id() + ";"),
                        "the binding cookie carries the pending record id"),
                () -> assertTrue(setCookie.contains("; SameSite=Lax"),
                        "Lax: the browser sends it on the top-level GET the query-mode callback is: " + setCookie),
                () -> assertFalse(setCookie.contains("SameSite=None"),
                        "the cross-site binding control is not weakened to make the callback work"));
    }

    @Test
    @DisplayName("Composed: a push the identity provider refuses stores no record and sets no cookie (matched control)")
    void shouldStoreNothingWhenTheComposedPushIsRefused(URIBuilder uriBuilder) {
        LoginFlow composed = composedLoginFlow(uriBuilder);
        pushAnswers.add(json(500, "{\"error\":\"server_error\"}"));

        GatewayException refused = assertThrows(GatewayException.class,
                () -> composed.initiate("/dashboard", SCOPES, QUOTA_KEY, T0));

        assertAll("a refused push leaves no login half-started",
                () -> assertEquals(EventType.UPSTREAM_ERROR, refused.getEventType()),
                () -> assertEquals("Pushed authorization request refused (push-failed)", refused.getMessage()),
                () -> assertEquals(1, pushedBodies.size(), "the push did reach the endpoint"),
                () -> assertEquals(0, pendingStore.size(), "nothing is stored, so no binding cookie can name it"));
    }

    /** The login flow as the runtime binds it: the seam is {@link ScopedEngineFlows#authorize}. */
    private LoginFlow composedLoginFlow(URIBuilder uriBuilder) {
        ClientConfiguration configuration = ClientConfiguration.builder()
                .issuer("https://idp.example.com").clientId(CLIENT_ID).clientSecret("secret")
                .authMethod(ClientAuthMethod.CLIENT_SECRET_BASIC)
                .scopes(List.of()).redirectUri(GATEWAY_ORIGIN + "/auth/callback")
                .allowInsecureHttp(true).allowLoopbackEgress(true)
                .build();
        TokenValidator validator = TokenValidator.builder()
                .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();
        TokenEndpointClient tokenClient = new TokenEndpointClient(configuration);
        TokenValidationBridge tokenBridge = new TokenValidationBridge(validator);
        ClientAuthentication authentication = new ClientSecretBasicAuth(CLIENT_ID, "secret");
        ScopedEngineFlows seam = new ScopedEngineFlows(configuration,
                new AuthorizationCodeFlow(configuration, tokenClient, tokenBridge, new IdTokenValidationBridge(validator)),
                new RefreshFlow(configuration, tokenClient, tokenBridge, authentication), authentication);
        ProviderMetadata metadata = new ProviderMetadata();
        metadata.issuer = "https://idp.example.com";
        metadata.authorizationEndpoint = IDP_AUTHORIZATION_ENDPOINT;
        metadata.pushedAuthorizationRequestEndpoint = uriBuilder.addPathSegment("par").buildAsString();
        metadata.codeChallengeMethodsSupported = List.of(ProviderMetadata.CODE_CHALLENGE_METHOD_S256);
        return new LoginFlow(scopes -> seam.authorize(metadata, scopes), pendingStore, bindingCodec, GATEWAY_ORIGIN,
                CONFIGURED_DEFAULT, CLOCK);
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body)
                .build();
    }

    /** Decodes a form-encoded string into its pairs, refusing a name that occurs twice. */
    private static Map<String, String> pairs(String encoded) {
        Map<String, String> pairs = new LinkedHashMap<>();
        for (String pair : encoded.split("&")) {
            String[] nameValue = pair.split("=", 2);
            String name = URLDecoder.decode(nameValue[0], StandardCharsets.UTF_8);
            assertNull(pairs.put(name, nameValue.length == 2
                            ? URLDecoder.decode(nameValue[1], StandardCharsets.UTF_8) : ""),
                    "the parameter " + name + " occurs twice");
        }
        return pairs;
    }

    @Nested
    @DisplayName("Engine-driven redirect")
    class Redirect {

        @Test
        @DisplayName("Should redirect to the engine authorization URL and set the binding cookie")
        void shouldRedirectToAuthorizationUrl() {
            LoginRedirect result = loginFlow.initiate("/dashboard", SCOPES, QUOTA_KEY, T0);

            assertEquals(AUTHORIZATION_URL, result.authorizationUrl(), "the URL comes from the engine, unchanged");
            assertEquals(1, result.setCookieHeaders().size());
            assertTrue(result.setCookieHeaders().getFirst().startsWith(BindingCookieCodec.COOKIE_NAME + "="));
        }

        @Test
        @DisplayName("Should persist the engine FlowContext as a single-use pending record bound to the browser")
        void shouldPersistPendingRecord() {
            LoginRedirect result = loginFlow.initiate("/dashboard", SCOPES, QUOTA_KEY, T0);

            PendingAuthorizationRecord pending = consumeBoundRecord(result);
            assertSame(flowContext, pending.flowContext(), "the record wraps the engine context, never re-invents it");
            assertEquals("/dashboard", pending.returnUrl());
            assertEquals(T0, pending.createdAt());
        }

        @Test
        @DisplayName("Should record the requester's quota key on the stored pending record")
        void shouldRecordQuotaKey() {
            LoginRedirect result = loginFlow.initiate("/dashboard", SCOPES, QUOTA_KEY, T0);

            assertEquals(QUOTA_KEY, consumeBoundRecord(result).quotaKey(),
                    "the store counts the record under the source that started the login");
        }

        @Test
        @DisplayName("Should forward the requested scope set to the authorization seam unchanged")
        void shouldForwardScopesToSeam() {
            loginFlow.initiate("/dashboard", SCOPES, QUOTA_KEY, T0);

            assertEquals(SCOPES, requestedScopes.get(), "the seam receives exactly the scope set the caller named");
        }
    }

    /**
     * The runtime binds the seam to the engine authorization followed by the push of the request
     * (ADR-0058), so a push that fails surfaces here as a throwing seam. The flow calls the seam
     * before it stores anything, which is what keeps a refused login from being left half-started.
     */
    @Nested
    @DisplayName("A failing authorization seam")
    class FailingSeam {

        @Test
        @DisplayName("Should propagate the seam's failure, store no pending record and yield no binding cookie")
        void shouldStoreNothingWhenTheSeamThrows() {
            GatewayException refusal =
                    new GatewayException(EventType.UPSTREAM_ERROR, "Pushed authorization request refused (push-failed)");
            LoginFlow failing = new LoginFlow(scopes -> {
                throw refusal;
            }, pendingStore, bindingCodec, GATEWAY_ORIGIN, CONFIGURED_DEFAULT, CLOCK);

            GatewayException thrown =
                    assertThrows(GatewayException.class, () -> failing.initiate("/dashboard", SCOPES, QUOTA_KEY, T0));

            assertSame(refusal, thrown, "the refusal reaches the caller as it was raised — no redirect, so no Set-Cookie");
            assertEquals(0, pendingStore.size(), "nothing is stored for a request that was never pushed");
        }

        @Test
        @DisplayName("Should store exactly one pending record when the seam yields a redirect (matched control)")
        void shouldStoreOneRecordWhenTheSeamYieldsARedirect() {
            LoginRedirect result = loginFlow.initiate("/dashboard", SCOPES, QUOTA_KEY, T0);

            assertEquals(1, pendingStore.size(), "the store does count a record, so the zero above is observed");
            assertEquals(1, result.setCookieHeaders().size(), "and the redirect carries the binding cookie");
        }
    }

    @Nested
    @DisplayName("A saturated pending store")
    class SaturatedStore {

        @Test
        @DisplayName("Should propagate the store's refusal after the push and yield no binding cookie")
        void shouldPropagateSaturationRefusal() {
            InMemoryPendingAuthorizationStore saturated = new InMemoryPendingAuthorizationStore(1);
            PendingAuthorizationRecord held = PendingAuthorizationRecord.create(flowContext, "/held", SCOPES,
                    OTHER_QUOTA_KEY, T0);
            saturated.store(held, T0);
            LoginFlow flow = new LoginFlow(authorization, saturated, bindingCodec, GATEWAY_ORIGIN, CONFIGURED_DEFAULT,
                    CLOCK);

            GatewayException thrown =
                    assertThrows(GatewayException.class, () -> flow.initiate("/dashboard", SCOPES, QUOTA_KEY, T0));

            assertEquals(EventType.PENDING_AUTHORIZATION_SATURATED, thrown.getEventType(),
                    "the refusal reaches the caller instead of a redirect, so no Set-Cookie is returned");
            assertEquals(SCOPES, requestedScopes.get(), "the refusal follows the push, like the 502 path");
            assertEquals(1, saturated.size(), "no record was added");
            assertTrue(saturated.consume(held.id(), T0).isPresent(), "the other source's live record was not evicted");
        }

        /**
         * The store is full at the caller's {@code now}, but its one record expires while the push is in
         * flight: the clock is moved past that expiry inside the authorization seam. The flow must let
         * the store purge at the reading taken after the push, so the login is admitted — while the new
         * record's own lifetime still runs from the caller's {@code now}.
         */
        @Test
        @DisplayName("Should admit the login when the held record expired during the push, its TTL anchored at now")
        void shouldAdmitWhenTheHeldRecordExpiredDuringThePush() {
            InMemoryPendingAuthorizationStore full = new InMemoryPendingAuthorizationStore(1);
            Instant heldCreatedAt = T0.minus(PendingAuthorizationRecord.FIXED_TTL).plusSeconds(1);
            full.store(PendingAuthorizationRecord.create(flowContext, "/held", SCOPES, OTHER_QUOTA_KEY,
                    heldCreatedAt), heldCreatedAt);
            SettableClock clock = new SettableClock(T0);
            AuthorizationCodeFlow.AuthorizationRedirect redirect =
                    new AuthorizationCodeFlow.AuthorizationRedirect(AUTHORIZATION_URL, flowContext);
            LoginFlow flow = new LoginFlow(scopes -> {
                clock.set(T0.plusSeconds(2));
                return redirect;
            }, full, bindingCodec, GATEWAY_ORIGIN, CONFIGURED_DEFAULT, clock);

            LoginRedirect result = flow.initiate("/dashboard", SCOPES, QUOTA_KEY, T0);

            String recordId = bindingCodec.readRecordId(result.setCookieHeaders().getFirst().split(";", 2)[0])
                    .orElseThrow();
            PendingAuthorizationRecord stored = full.consume(recordId, T0).orElseThrow();
            assertAll("admitted at the post-push reading, anchored at the caller's now",
                    () -> assertEquals(T0, stored.createdAt(), "the push latency does not move the TTL anchor"),
                    () -> assertEquals(T0.plus(PendingAuthorizationRecord.FIXED_TTL), stored.expiresAt()));
        }
    }

    @Nested
    @DisplayName("Same-origin return-URL guard (never an open redirect)")
    class ReturnUrlGuard {

        @ParameterizedTest(name = "same-origin return URL \"{0}\" is recorded verbatim")
        @ValueSource(strings = {"/dashboard", "/app/page?x=1", "/x?tab=a&b", "/x?a=1&b=2&a=3&q=a%20b",
                "https://gw.example.com/app"})
        @DisplayName("Should record a same-origin return URL verbatim, query included")
        void shouldRecordSameOriginReturnUrl(String returnUrl) {
            LoginRedirect result = loginFlow.initiate(returnUrl, SCOPES, QUOTA_KEY, T0);

            assertEquals(returnUrl, consumeBoundRecord(result).returnUrl());
        }

        @ParameterizedTest(name = "off-origin return URL \"{0}\" falls back to the configured default")
        @ValueSource(strings = {"https://evil.example.com/app", "//evil.example.com", "javascript:alert(1)"})
        @DisplayName("Should fall back to the configured default for a cross-origin or unparseable return URL")
        void shouldFallBackForOffOrigin(String returnUrl) {
            LoginRedirect result = loginFlow.initiate(returnUrl, SCOPES, QUOTA_KEY, T0);

            assertEquals(CONFIGURED_DEFAULT, consumeBoundRecord(result).returnUrl());
        }

        @Test
        @DisplayName("Should fall back to the configured default when no return URL is supplied")
        void shouldFallBackForNull() {
            LoginRedirect result = loginFlow.initiate(null, SCOPES, QUOTA_KEY, T0);

            assertEquals(CONFIGURED_DEFAULT, consumeBoundRecord(result).returnUrl());
        }

        @Test
        @DisplayName("Should fall back to '/' when the flow is assembled with the unset default")
        void shouldFallBackToRootDefault() {
            LoginFlow rootDefault = new LoginFlow(authorization, pendingStore, bindingCodec, GATEWAY_ORIGIN, "/", CLOCK);

            LoginRedirect absent = rootDefault.initiate(null, SCOPES, QUOTA_KEY, T0);
            LoginRedirect offOrigin = rootDefault.initiate("https://evil.example.com/app", SCOPES, QUOTA_KEY, T0);

            assertEquals("/", consumeBoundRecord(absent).returnUrl(), "an absent target lands on '/'");
            assertEquals("/", consumeBoundRecord(offOrigin).returnUrl(), "an off-origin target lands on '/'");
        }

        @Test
        @DisplayName("Should expose the configured default return URL")
        void shouldExposeConfiguredDefault() {
            assertEquals(CONFIGURED_DEFAULT, loginFlow.defaultReturnUrl());
        }
    }

    @Nested
    @DisplayName("Argument contract")
    class ArgumentContract {

        @Test
        @DisplayName("Should reject a null reference instant")
        void shouldRejectNullNow() {
            assertThrows(NullPointerException.class, () -> loginFlow.initiate("/dashboard", SCOPES, QUOTA_KEY, null));
        }

        @Test
        @DisplayName("Should reject a null scope set before reaching the seam")
        void shouldRejectNullScopes() {
            assertThrows(NullPointerException.class, () -> loginFlow.initiate("/dashboard", null, QUOTA_KEY, T0));
            assertNull(requestedScopes.get(), "the seam is never reached without a scope set");
        }

        @Test
        @DisplayName("Should reject a null quota key before reaching the seam")
        void shouldRejectNullQuotaKey() {
            assertThrows(NullPointerException.class, () -> loginFlow.initiate("/dashboard", SCOPES, null, T0));
            assertNull(requestedScopes.get(), "the seam is never reached without a quota key");
        }

        @Test
        @DisplayName("Should reject a null default return URL")
        void shouldRejectNullDefaultReturnUrl() {
            assertThrows(NullPointerException.class,
                    () -> new LoginFlow(authorization, pendingStore, bindingCodec, GATEWAY_ORIGIN, null, CLOCK));
        }

        @Test
        @DisplayName("Should reject a null clock")
        void shouldRejectNullClock() {
            assertThrows(NullPointerException.class,
                    () -> new LoginFlow(authorization, pendingStore, bindingCodec, GATEWAY_ORIGIN, CONFIGURED_DEFAULT,
                            null));
        }
    }
}
