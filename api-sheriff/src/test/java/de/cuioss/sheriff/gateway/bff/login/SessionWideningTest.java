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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import de.cuioss.sheriff.gateway.bff.login.LoginFlow.LoginRedirect;
import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.pending.InMemoryPendingAuthorizationStore;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord.Widening;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link SessionWidening}: the live-session widening requests the granted set united with the
 * route's needed scopes, records the attempt and the live identity on a widening pending record, never
 * redirects off-origin, emits the browser-binding cookie, and re-drives exactly the silent attempt as
 * one interactive attempt.
 * <p>
 * The engine authorization is driven through the {@link SessionWidening.AuthorizationWidening} seam
 * with a hand-built {@link AuthorizationCodeFlow.AuthorizationRedirect}, so the widening is exercised
 * without a live IdP or discovery round-trip. The composed cases at the top level bind the seam the way
 * the runtime does — to {@link ScopedEngineFlows#widen} over the engine's own flow — against an
 * in-process pushed-authorization-request endpoint, so the silent attempt's {@code prompt=none}, the
 * interactive re-drive's absent {@code prompt}, the canonical scope set and the pushed-request location
 * are pinned on what the identity provider and the browser actually receive. They sit at the top level
 * because the mock server resolves its dispatcher from the test instance.
 */
@EnableGeneratorController
@EnableMockWebServer
@ModuleDispatcher
@DisplayName("SessionWidening — prompt=none first, recorded on a widening pending record")
class SessionWideningTest {

    private static final Instant T0 = Instant.parse("2026-09-25T10:00:00Z");
    /** No time passes during a push unless a case moves it. */
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final String GATEWAY_ORIGIN = "https://gw.example.com";
    private static final String AUTHORIZATION_URL = "https://idp.example.com/authorize?client_id=api-sheriff";
    /** A configured {@code oidc.login.default_return_url} distinct from {@code /}. */
    private static final String CONFIGURED_DEFAULT = "/home";
    private static final Set<String> GRANTED = Set.of("openid", "profile");
    private static final Set<String> NEEDED = Set.of("openid", "orders:read");
    /** The requester source a widening is counted under. */
    private static final String QUOTA_KEY = "192.0.2.10";
    /** The source of the callback request that re-drives a silent widening. */
    private static final String REDRIVE_QUOTA_KEY = "198.51.100.20";
    private static final String IDP_AUTHORIZATION_ENDPOINT = "https://idp.example.com/authorize";
    private static final String CLIENT_ID = "api-sheriff";
    private static final String REQUEST_URI = "urn:ietf:params:oauth:request_uri:session-widening-test";

    /** One widening-seam call: the scope set it was asked for and whether it was silent. */
    private record SeamCall(Collection<String> scopes, boolean silent) {
    }

    private InMemoryPendingAuthorizationStore pendingStore;
    private BindingCookieCodec bindingCodec;
    private FlowContext flowContext;
    private List<SeamCall> seamCalls;
    private SessionWidening.AuthorizationWidening authorization;
    private SessionWidening widening;
    private SessionRecord live;

    @BeforeEach
    void setUp() {
        pendingStore = new InMemoryPendingAuthorizationStore(8);
        bindingCodec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
        flowContext = FlowContext.create(GATEWAY_ORIGIN + "/auth/callback");
        AuthorizationCodeFlow.AuthorizationRedirect redirect =
                new AuthorizationCodeFlow.AuthorizationRedirect(AUTHORIZATION_URL, flowContext);
        seamCalls = new ArrayList<>();
        authorization = (scopes, silent) -> {
            seamCalls.add(new SeamCall(scopes, silent));
            return redirect;
        };
        widening = new SessionWidening(authorization, pendingStore, bindingCodec, GATEWAY_ORIGIN, CONFIGURED_DEFAULT,
                CLOCK);
        live = liveSession(GRANTED);
    }

    private static SessionRecord liveSession(Set<String> granted) {
        return SessionRecord.builder()
                .sessionId(SessionRecord.newSessionId())
                .accessToken(Generators.letterStrings(16, 32).next())
                .idToken(Generators.letterStrings(16, 32).next())
                .sub(Generators.letterStrings(8, 16).next())
                .expiresAt(T0.plusSeconds(3600))
                .activeScopes(granted)
                .grantedScopes(granted)
                .build();
    }

    private PendingAuthorizationRecord consumeBoundRecord(LoginRedirect result) {
        String cookieHeader = result.setCookieHeaders().getFirst().split(";", 2)[0];
        Optional<String> recordId = bindingCodec.readRecordId(cookieHeader);
        assertTrue(recordId.isPresent(), "the widening sets a binding cookie carrying the record id");
        return pendingStore.consume(recordId.get(), T0).orElseThrow();
    }

    // ---------------------------------------------------------------------------------------------
    // Composed with the engine seam the runtime binds
    // ---------------------------------------------------------------------------------------------

    /** Every form body the pushed-authorization-request endpoint received. */
    private final List<String> pushedBodies = new CopyOnWriteArrayList<>();
    /** Whether the endpoint refuses every push with {@code 500}. */
    private volatile boolean refusePushes;

    /**
     * The pushed-authorization-request endpoint: records each push and answers {@code 201} with
     * {@link #REQUEST_URI}, or {@code 500} when {@link #refusePushes} is set.
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
                return Optional.of(refusePushes
                        ? json(500, "{\"error\":\"server_error\"}")
                        : json(201, "{\"request_uri\":\"" + REQUEST_URI + "\",\"expires_in\":60}"));
            }

            @Override
            public Set<HttpMethodMapper> supportedMethods() {
                return Set.of(HttpMethodMapper.POST);
            }
        };
    }

    @Test
    @DisplayName("Composed: the silent attempt pushes prompt=none once, the interactive re-drive pushes no prompt")
    void shouldPushPromptNoneOnTheSilentAttemptOnly(URIBuilder uriBuilder) {
        SessionWidening composed = composedWidening(uriBuilder);

        LoginRedirect silent = composed.initiate(live, "/orders", NEEDED, Widening.Attempt.SILENT, QUOTA_KEY, T0);
        LoginRedirect interactive = composed.redriveInteractive(consumeBoundRecord(silent), REDRIVE_QUOTA_KEY, T0);

        assertEquals(2, pushedBodies.size(), "each attempt pushed its own request");
        String silentPush = pushedBodies.getFirst();
        String interactivePush = pushedBodies.getLast();
        assertAll("the prompt of each widening attempt, as pushed",
                () -> assertEquals(List.of("none"), values(silentPush, "prompt"),
                        "exactly one prompt=none on the silent attempt"),
                () -> assertEquals(List.of(), values(interactivePush, "prompt"),
                        "matched control: the interactive re-drive lets the identity provider interact"),
                () -> assertEquals(List.of("openid orders:read profile"), values(silentPush, "scope"),
                        "the granted set united with the needed scopes, canonical"),
                () -> assertEquals(values(silentPush, "scope"), values(interactivePush, "scope"),
                        "the re-drive requests the silent attempt's set"),
                () -> assertEquals(List.of("query"), values(silentPush, "response_mode")),
                () -> assertEquals(List.of("query"), values(interactivePush, "response_mode")));
        for (LoginRedirect redirect : List.of(silent, interactive)) {
            assertAll("the location of a pushed widening",
                    () -> assertEquals(Map.of("client_id", CLIENT_ID, "request_uri", REQUEST_URI),
                            pairs(redirect.authorizationUrl().substring(IDP_AUTHORIZATION_ENDPOINT.length() + 1)),
                            "client_id and request_uri, no prompt and no other authorization parameter"),
                    () -> assertTrue(redirect.setCookieHeaders().getFirst().contains("; SameSite=Lax")));
        }
    }

    @Test
    @DisplayName("Composed: a refused widening push stores no record (matched control of the composed case)")
    void shouldStoreNothingWhenTheComposedWideningPushIsRefused(URIBuilder uriBuilder) {
        SessionWidening composed = composedWidening(uriBuilder);
        refusePushes = true;

        GatewayException refused = assertThrows(GatewayException.class,
                () -> composed.initiate(live, "/orders", NEEDED, Widening.Attempt.SILENT, QUOTA_KEY, T0));

        assertEquals(EventType.UPSTREAM_ERROR, refused.getEventType());
        assertEquals("Pushed authorization request refused (push-failed)", refused.getMessage());
        assertEquals(1, pushedBodies.size(), "the push did reach the endpoint");
        assertEquals(0, pendingStore.size(), "no widening is left half-started");
    }

    /** The widening as the runtime binds it: the seam is {@link ScopedEngineFlows#widen}. */
    private SessionWidening composedWidening(URIBuilder uriBuilder) {
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
        return new SessionWidening((scopes, silent) -> seam.widen(metadata, scopes, silent), pendingStore,
                bindingCodec, GATEWAY_ORIGIN, CONFIGURED_DEFAULT, CLOCK);
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body)
                .build();
    }

    /** Every value of {@code name} in a form-encoded body, once per occurrence. */
    private static List<String> values(String encoded, String name) {
        return Arrays.stream(encoded.split("&"))
                .map(pair -> pair.split("=", 2))
                .filter(nameValue -> name.equals(URLDecoder.decode(nameValue[0], StandardCharsets.UTF_8)))
                .map(nameValue -> nameValue.length == 2 ? URLDecoder.decode(nameValue[1], StandardCharsets.UTF_8) : "")
                .toList();
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
    @DisplayName("Requested scope set")
    class RequestedScopes {

        @Test
        @DisplayName("Should request the granted set united with the needed scopes, never the needed scopes alone")
        void shouldRequestGrantedUnionNeeded() {
            LoginRedirect result = widening.initiate(live, "/orders", NEEDED, Widening.Attempt.SILENT, QUOTA_KEY, T0);

            Set<String> expected = Set.of("openid", "profile", "orders:read");
            assertEquals(1, seamCalls.size(), "exactly one authorization request is built");
            assertEquals(expected, Set.copyOf(seamCalls.getFirst().scopes()),
                    "a narrower request would silently narrow the live session on the callback");
            assertEquals(expected, consumeBoundRecord(result).requestedScopes(),
                    "the pending record carries the set the request asked for");
        }

        @Test
        @DisplayName("Should request the granted set unchanged when every needed scope is already granted")
        void shouldRequestGrantedWhenNeededIsCovered() {
            widening.initiate(live, "/orders", Set.of("profile"), Widening.Attempt.SILENT, QUOTA_KEY, T0);

            assertEquals(GRANTED, Set.copyOf(seamCalls.getFirst().scopes()));
        }
    }

    @Nested
    @DisplayName("Attempt recorded on the pending record")
    class AttemptRecorded {

        @Test
        @DisplayName("Should issue the silent attempt with prompt=none and record it with the live subject")
        void shouldRecordSilentAttempt() {
            LoginRedirect result = widening.initiate(live, "/orders", NEEDED, Widening.Attempt.SILENT, QUOTA_KEY, T0);

            PendingAuthorizationRecord pending = consumeBoundRecord(result);
            assertTrue(seamCalls.getFirst().silent(), "a silent attempt asks the seam for prompt=none");
            assertNotNull(pending.widening(), "a widening leaves a widening record, never a login record");
            assertEquals(Widening.Attempt.SILENT, pending.widening().attempt());
            assertEquals(live.sub(), pending.widening().sub(), "the callback must land on the live identity");
            assertSame(flowContext, pending.flowContext(), "the record wraps the engine context");
            assertEquals(T0, pending.createdAt());
            assertEquals(QUOTA_KEY, pending.quotaKey(), "the widening record is counted under its requester");
        }

        @Test
        @DisplayName("Should issue the interactive attempt without prompt=none and record it as interactive")
        void shouldRecordInteractiveAttempt() {
            LoginRedirect result = widening.initiate(live, "/orders", NEEDED, Widening.Attempt.INTERACTIVE, QUOTA_KEY, T0);

            PendingAuthorizationRecord pending = consumeBoundRecord(result);
            assertFalse(seamCalls.getFirst().silent(), "the interactive attempt lets the IdP interact");
            assertEquals(Widening.Attempt.INTERACTIVE, pending.widening().attempt());
        }
    }

    @Nested
    @DisplayName("Interactive re-drive")
    class InteractiveRedrive {

        @Test
        @DisplayName("Should re-drive a silent record as one interactive attempt with the same URL, scopes and subject")
        void shouldRedriveSilentAsInteractive() {
            PendingAuthorizationRecord silent = consumeBoundRecord(
                    widening.initiate(live, "/orders", NEEDED, Widening.Attempt.SILENT, QUOTA_KEY, T0));

            LoginRedirect redrive = widening.redriveInteractive(silent, REDRIVE_QUOTA_KEY, T0.plusSeconds(5));

            PendingAuthorizationRecord interactive = consumeBoundRecord(redrive);
            assertEquals(AUTHORIZATION_URL, redrive.authorizationUrl());
            assertEquals(2, seamCalls.size());
            assertFalse(seamCalls.get(1).silent(), "the re-drive is the interactive attempt");
            assertEquals(silent.requestedScopes(), Set.copyOf(seamCalls.get(1).scopes()),
                    "the re-drive asks for the same set as the silent attempt");
            assertEquals(Widening.Attempt.INTERACTIVE, interactive.widening().attempt());
            assertEquals(silent.widening().sub(), interactive.widening().sub());
            assertEquals(silent.returnUrl(), interactive.returnUrl());
            assertEquals(silent.requestedScopes(), interactive.requestedScopes());
            assertNotEquals(silent.id(), interactive.id(), "the re-drive persists a fresh single-use record");
            assertEquals(REDRIVE_QUOTA_KEY, interactive.quotaKey(),
                    "the re-drive record is counted under the source of the request that re-drives it");
        }

        @Test
        @DisplayName("Should refuse to re-drive an interactive record — the interactive attempt is the last")
        void shouldRefuseToRedriveInteractive() {
            PendingAuthorizationRecord interactive = consumeBoundRecord(
                    widening.initiate(live, "/orders", NEEDED, Widening.Attempt.INTERACTIVE, QUOTA_KEY, T0));

            assertThrows(IllegalArgumentException.class,
                    () -> widening.redriveInteractive(interactive, QUOTA_KEY, T0));
            assertEquals(1, seamCalls.size(), "no second authorization request is built");
        }

        @Test
        @DisplayName("Should refuse to re-drive a plain login record")
        void shouldRefuseToRedriveLogin() {
            PendingAuthorizationRecord login = PendingAuthorizationRecord.create(flowContext, "/", NEEDED, QUOTA_KEY,
                    T0);

            assertThrows(IllegalArgumentException.class, () -> widening.redriveInteractive(login, QUOTA_KEY, T0));
            assertTrue(seamCalls.isEmpty(), "the seam is never reached for a login record");
        }
    }

    @Nested
    @DisplayName("Same-origin return-URL guard (never an open redirect)")
    class ReturnUrlGuard {

        @ParameterizedTest(name = "same-origin return URL \"{0}\" is recorded verbatim")
        @ValueSource(strings = {"/orders", "/app/page?x=1&y=2", "https://gw.example.com/app"})
        @DisplayName("Should record a same-origin return URL verbatim")
        void shouldRecordSameOriginReturnUrl(String returnUrl) {
            LoginRedirect result = widening.initiate(live, returnUrl, NEEDED, Widening.Attempt.SILENT, QUOTA_KEY, T0);

            assertEquals(returnUrl, consumeBoundRecord(result).returnUrl());
        }

        @ParameterizedTest(name = "off-origin return URL \"{0}\" falls back to the configured default")
        @ValueSource(strings = {"https://evil.example.com/app", "//evil.example.com", "/\\evil.example.com",
                "/\t/evil.example.com", "javascript:alert(1)", " "})
        @DisplayName("Should fall back to the configured default for a cross-origin, smuggled or blank return URL")
        void shouldFallBackForOffOrigin(String returnUrl) {
            LoginRedirect result = widening.initiate(live, returnUrl, NEEDED, Widening.Attempt.SILENT, QUOTA_KEY, T0);

            assertEquals(CONFIGURED_DEFAULT, consumeBoundRecord(result).returnUrl());
        }

        @Test
        @DisplayName("Should fall back to the configured default when no return URL is supplied")
        void shouldFallBackForNull() {
            LoginRedirect result = widening.initiate(live, null, NEEDED, Widening.Attempt.SILENT, QUOTA_KEY, T0);

            assertEquals(CONFIGURED_DEFAULT, consumeBoundRecord(result).returnUrl());
        }
    }

    @Nested
    @DisplayName("Browser redirect")
    class BrowserRedirect {

        @Test
        @DisplayName("Should redirect to the engine authorization URL and set exactly the binding cookie")
        void shouldRedirectWithBindingCookie() {
            LoginRedirect result = widening.initiate(live, "/orders", NEEDED, Widening.Attempt.SILENT, QUOTA_KEY, T0);

            assertEquals(AUTHORIZATION_URL, result.authorizationUrl(), "the URL comes from the seam, unchanged");
            assertEquals(1, result.setCookieHeaders().size(), "no session cookie is touched by the initiation");
            assertTrue(result.setCookieHeaders().getFirst().startsWith(BindingCookieCodec.COOKIE_NAME + "="));
        }
    }

    @Nested
    @DisplayName("Argument contract")
    class ArgumentContract {

        @Test
        @DisplayName("Should reject absent arguments before reaching the seam")
        void shouldRejectNullArguments() {
            assertThrows(NullPointerException.class,
                    () -> widening.initiate(null, "/orders", NEEDED, Widening.Attempt.SILENT, QUOTA_KEY, T0));
            assertThrows(NullPointerException.class,
                    () -> widening.initiate(live, "/orders", null, Widening.Attempt.SILENT, QUOTA_KEY, T0));
            assertThrows(NullPointerException.class,
                    () -> widening.initiate(live, "/orders", NEEDED, null, QUOTA_KEY, T0));
            assertThrows(NullPointerException.class,
                    () -> widening.initiate(live, "/orders", NEEDED, Widening.Attempt.SILENT, null, T0));
            assertThrows(NullPointerException.class,
                    () -> widening.initiate(live, "/orders", NEEDED, Widening.Attempt.SILENT, QUOTA_KEY, null));
            assertTrue(seamCalls.isEmpty(), "the seam is never reached with an absent argument");
        }

        @Test
        @DisplayName("Should reject an absent quota key on the re-drive before reaching the seam")
        void shouldRejectNullRedriveQuotaKey() {
            PendingAuthorizationRecord silent = PendingAuthorizationRecord.createWidening(flowContext, "/orders",
                    NEEDED, live.sub(), Widening.Attempt.SILENT, QUOTA_KEY, T0);

            assertThrows(NullPointerException.class, () -> widening.redriveInteractive(silent, null, T0));
            assertTrue(seamCalls.isEmpty(), "the seam is never reached without a quota key");
        }

        @Test
        @DisplayName("Should reject an absent collaborator")
        void shouldRejectNullCollaborators() {
            assertThrows(NullPointerException.class,
                    () -> new SessionWidening(null, pendingStore, bindingCodec, GATEWAY_ORIGIN, CONFIGURED_DEFAULT,
                            CLOCK));
            assertThrows(NullPointerException.class,
                    () -> new SessionWidening(authorization, pendingStore, bindingCodec, GATEWAY_ORIGIN, null, CLOCK));
            assertThrows(NullPointerException.class,
                    () -> new SessionWidening(authorization, pendingStore, bindingCodec, GATEWAY_ORIGIN,
                            CONFIGURED_DEFAULT, null));
        }
    }

    /**
     * A store full at the caller's {@code now} whose one record expires while the push is in flight —
     * the clock is moved past that expiry inside the authorization seam. The widening must let the store
     * purge at the reading taken after the push, so it is admitted, while the new record's lifetime
     * still runs from the caller's {@code now}.
     */
    @Nested
    @DisplayName("A store whose records expired during the push")
    class ExpiredDuringThePush {

        private InMemoryPendingAuthorizationStore full;
        private SessionWidening pushingPastExpiry;

        @BeforeEach
        void fillTheStore() {
            full = new InMemoryPendingAuthorizationStore(1);
            Instant heldCreatedAt = T0.minus(PendingAuthorizationRecord.FIXED_TTL).plusSeconds(1);
            full.store(PendingAuthorizationRecord.create(flowContext, "/held", NEEDED, REDRIVE_QUOTA_KEY,
                    heldCreatedAt), heldCreatedAt);
            SettableClock clock = new SettableClock(T0);
            AuthorizationCodeFlow.AuthorizationRedirect redirect =
                    new AuthorizationCodeFlow.AuthorizationRedirect(AUTHORIZATION_URL, flowContext);
            pushingPastExpiry = new SessionWidening((scopes, silent) -> {
                clock.set(T0.plusSeconds(2));
                return redirect;
            }, full, bindingCodec, GATEWAY_ORIGIN, CONFIGURED_DEFAULT, clock);
        }

        @Test
        @DisplayName("Should admit a silent widening, its TTL anchored at now")
        void shouldAdmitSilentWidening() {
            LoginRedirect result = pushingPastExpiry.initiate(live, "/orders", NEEDED, Widening.Attempt.SILENT,
                    QUOTA_KEY, T0);

            assertAnchoredAtT0(storedIn(result));
        }

        @Test
        @DisplayName("Should admit the interactive re-drive, its TTL anchored at now")
        void shouldAdmitInteractiveRedrive() {
            PendingAuthorizationRecord silent = PendingAuthorizationRecord.createWidening(flowContext, "/orders",
                    NEEDED, live.sub(), Widening.Attempt.SILENT, QUOTA_KEY, T0);

            LoginRedirect result = pushingPastExpiry.redriveInteractive(silent, QUOTA_KEY, T0);

            assertAnchoredAtT0(storedIn(result));
        }

        private PendingAuthorizationRecord storedIn(LoginRedirect result) {
            String recordId = bindingCodec.readRecordId(result.setCookieHeaders().getFirst().split(";", 2)[0])
                    .orElseThrow();
            return full.consume(recordId, T0).orElseThrow();
        }

        private void assertAnchoredAtT0(PendingAuthorizationRecord stored) {
            assertAll("admitted at the post-push reading, anchored at the caller's now",
                    () -> assertEquals(T0, stored.createdAt(), "the push latency does not move the TTL anchor"),
                    () -> assertEquals(T0.plus(PendingAuthorizationRecord.FIXED_TTL), stored.expiresAt()));
        }
    }
}
