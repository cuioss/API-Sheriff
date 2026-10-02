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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;


import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.sheriff.gateway.bff.client.TestSigningKeys;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.testsupport.SheriffDebugCapture;
import de.cuioss.sheriff.token.client.auth.ClientAuthentication;
import de.cuioss.sheriff.token.client.auth.ClientSecretBasicAuth;
import de.cuioss.sheriff.token.client.auth.PrivateKeyJwtAuth;
import de.cuioss.sheriff.token.client.config.ClientAuthMethod;
import de.cuioss.sheriff.token.client.config.ClientConfiguration;
import de.cuioss.sheriff.token.client.discovery.ProviderMetadata;
import de.cuioss.sheriff.token.client.flow.FlowContext;
import de.cuioss.sheriff.token.client.flow.ParClient;
import de.cuioss.sheriff.token.commons.error.TokenSheriffException;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import de.cuioss.test.mockwebserver.EnableMockWebServer;
import de.cuioss.test.mockwebserver.URIBuilder;
import de.cuioss.test.mockwebserver.dispatcher.HttpMethodMapper;
import de.cuioss.test.mockwebserver.dispatcher.ModuleDispatcher;
import de.cuioss.test.mockwebserver.dispatcher.ModuleDispatcherElement;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import okhttp3.Headers;
import okio.ByteString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link PushedAuthorizationRequests}: the parameters of an engine-built authorization
 * request are pushed to the identity provider exactly as the engine rendered them, the browser is
 * sent to the authorization endpoint with {@code client_id} and {@code request_uri} only, and each
 * failure staged here refuses the login with the {@code 502} event, recorded once per reason.
 * <p>
 * The adapter is driven with the engine's own collaborators: the authorization URL is built by the
 * gateway's request builder over an engine {@link FlowContext}, and the push goes through a real
 * {@link ParClient} to an in-process pushed-authorization-request endpoint that records each request
 * and answers from a script a test may fill. The endpoint is plain HTTP, which the hand-built client
 * configuration of this test allows; the TLS posture of the leg is proven on a produced runtime in
 * {@code BffRuntimeProducerTest}.
 * <p>
 * The tests sit at the top level on purpose: the mock server resolves its dispatcher from the test
 * instance, and a nested instance does not declare one.
 * <p>
 * The disclosure assertions read every captured record down to {@code DEBUG}, and the latch tests
 * count the adapter's own {@code DEBUG} records. The root level alone does not open the loggers of
 * the gateway and of the token library for that — see {@link SheriffDebugCapture} — so the extension
 * is registered here, and each disclosure assertion first proves that a {@code DEBUG} record of the
 * adapter and of the engine's {@link ParClient} is captured.
 */
@EnableGeneratorController
@EnableMockWebServer
@ModuleDispatcher
@EnableTestLogger(rootLevel = TestLogLevel.DEBUG)
@ExtendWith(SheriffDebugCapture.class)
@DisplayName("PushedAuthorizationRequests — the authorization request is pushed, or the login is refused")
class PushedAuthorizationRequestsTest {

    private static final String ISSUER = "https://idp.example.com";
    private static final String AUTHORIZATION_ENDPOINT = ISSUER + "/authorize";
    private static final String REDIRECT_URI = "https://gw.example.com/auth/callback";
    private static final String PAR_PATH = "/par";
    private static final String PAR_SEGMENT = "par";
    /** Carries characters the form encoding has to escape, so an unencoded redirect cannot pass. */
    private static final String CLIENT_ID = "gateway client:1&x=y";
    private static final List<String> SCOPES = List.of("openid", "profile", "orders:read");
    private static final String REQUEST_URI_PREFIX = "urn:ietf:params:oauth:request_uri:";
    private static final String PARAM_CLIENT_ID = "client_id";
    private static final String PARAM_REQUEST_URI = "request_uri";
    private static final String PARAM_STATE = "state";
    private static final String PARAM_NONCE = "nonce";
    private static final String PARAM_CODE_CHALLENGE = "code_challenge";
    private static final String CLIENT_ASSERTION = "client_assertion";
    private static final String CLIENT_ASSERTION_TYPE = "client_assertion_type";
    private static final String JWT_BEARER_ASSERTION = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    private static final String AUTHORIZATION = "Authorization";
    private static final String BASIC_SCHEME = "Basic ";
    private static final String DPOP_HEADER = "DPoP";
    private static final String REASON_NO_PAR_ENDPOINT = "no-par-endpoint";
    private static final String REASON_INVALID_REQUEST = "invalid-request";
    private static final String REASON_PUSH_FAILED = "push-failed";
    private static final String STEP_UP_ACR = "urn:example:gold";
    private static final int STEP_UP_MAX_AGE = 300;
    /** The read timeout the failing pushes run under, and the bound the slow answer exceeds. */
    private static final int READ_TIMEOUT_SECONDS = 1;
    private static final int SLOW_ANSWER_DELAY_SECONDS = 2;
    private static final QueryResponseModeAuthorizationRequestBuilder REQUEST_BUILDER =
            new QueryResponseModeAuthorizationRequestBuilder();

    /** The ways a push that reached the endpoint fails. */
    enum PushFailure {

        /** The endpoint answers {@code 500}. */
        SERVER_ERROR {
        @Override
        MockResponse answer() {
            return json(500, "{\"error\":\"server_error\"}");
        }
    },

        /** The endpoint refuses the request with {@code 400}. */
        CLIENT_ERROR {
            @Override
            MockResponse answer() {
                return json(400, "{\"error\":\"invalid_request\"}");
            }
        },

        /** The endpoint answers {@code 200} with a body that names no {@code request_uri}. */
        SUCCESS_WITHOUT_REQUEST_URI {
            @Override
            MockResponse answer() {
                return json(200, "{\"expires_in\":60}");
            }
        },

        /** The endpoint answers {@code 200} with a body that is not JSON. */
        UNPARSEABLE_BODY {
            @Override
            MockResponse answer() {
                return new MockResponse.Builder().code(200).addHeader("Content-Type", "text/html")
                        .body("<html>not a pushed authorization response</html>").build();
            }
        },

        /** The endpoint answers correctly, but later than the read timeout allows. */
        SLOWER_THAN_THE_READ_TIMEOUT {
            @Override
            MockResponse answer() {
                return new MockResponse.Builder().code(201).addHeader("Content-Type", "application/json")
                        .body("{\"request_uri\":\"" + REQUEST_URI_PREFIX + "late\",\"expires_in\":60}")
                        .headersDelay(SLOW_ANSWER_DELAY_SECONDS, TimeUnit.SECONDS).build();
            }
        };

        abstract MockResponse answer();
    }

    /** The ways an authorization URL is not a uniquely named parameter set on the authorization endpoint. */
    enum InvalidRequest {

        /** One parameter name occurs twice. */
        REPEATED_PARAMETER_NAME {
        @Override
        String corrupt(String authorizationUrl) {
            return authorizationUrl + "&state=another-state";
        }
    },

        /** The URL is built on another endpoint than the one the metadata names. */
        ANOTHER_ENDPOINT {
            @Override
            String corrupt(String authorizationUrl) {
                return authorizationUrl.replace(AUTHORIZATION_ENDPOINT, ISSUER + "/elsewhere");
            }
        },

        /** Nothing follows the endpoint. */
        NO_PARAMETER {
            @Override
            String corrupt(String authorizationUrl) {
                return AUTHORIZATION_ENDPOINT;
            }
        },

        /** Only the query separator follows the endpoint. */
        EMPTY_QUERY {
            @Override
            String corrupt(String authorizationUrl) {
                return AUTHORIZATION_ENDPOINT + "?";
            }
        },

        /** Something other than a query separator follows the endpoint. */
        NO_QUERY_SEPARATOR {
            @Override
            String corrupt(String authorizationUrl) {
                return AUTHORIZATION_ENDPOINT + "/path?state=a";
            }
        },

        /** A pair carries a value and no name. */
        PAIR_WITHOUT_A_NAME {
            @Override
            String corrupt(String authorizationUrl) {
                return authorizationUrl + "&=nameless";
            }
        },

        /** A pair is not valid form encoding. */
        UNDECODABLE_PAIR {
            @Override
            String corrupt(String authorizationUrl) {
                return authorizationUrl + "&broken=%zz";
            }
        };

        abstract String corrupt(String authorizationUrl);
    }

    /** The two authorization requests the runtime pushes. */
    enum PushedRequest {

        /** The request of a login: no authentication context is asked for. */
        LOGIN {
        @Override
        FlowContext context() {
            return FlowContext.create(REDIRECT_URI);
        }
    },

        /** The request of a step-up re-drive: an authentication context and an authentication age. */
        STEP_UP {
            @Override
            FlowContext context() {
                return FlowContext.create(REDIRECT_URI, STEP_UP_ACR, STEP_UP_MAX_AGE);
            }
        };

        abstract FlowContext context();
    }

    /**
     * One request the pushed-authorization-request endpoint received.
     *
     * @param headers the request headers
     * @param body    the form-encoded request body
     */
    private record Pushed(Headers headers, String body) {

        Map<String, String> form() {
            return formPairs(body);
        }

        Optional<String> header(String name) {
            return Optional.ofNullable(headers.get(name));
        }
    }

    private final ClientAuthentication keyAuthentication = new PrivateKeyJwtAuth(CLIENT_ID, ISSUER,
            TestSigningKeys.ecKeyPair().getPrivate(), "test-client-key", "ES256");
    /** Every request the endpoint received, in arrival order. */
    private final List<Pushed> received = new CopyOnWriteArrayList<>();
    /** The answers the endpoint serves next, one per request; empty means the accepting default. */
    private final Queue<MockResponse> scripted = new ConcurrentLinkedQueue<>();

    /**
     * The pushed-authorization-request endpoint: records each request, then answers from the script
     * and, once the script is empty, with {@code 201} and a {@code request_uri}.
     *
     * @return the endpoint dispatcher
     */
    public ModuleDispatcherElement getModuleDispatcher() {
        return new ModuleDispatcherElement() {

            @Override
            public String getBaseUrl() {
                return PAR_PATH;
            }

            @Override
            public Optional<MockResponse> handlePost(RecordedRequest request) {
                ByteString body = request.getBody();
                received.add(new Pushed(request.getHeaders(), body == null ? "" : body.utf8()));
                MockResponse next = scripted.poll();
                return Optional.of(next == null ? accepted(REQUEST_URI_PREFIX + "default") : next);
            }

            @Override
            public Set<HttpMethodMapper> supportedMethods() {
                return Set.of(HttpMethodMapper.POST);
            }
        };
    }

    @Test
    @DisplayName("Should push exactly the parameter set the engine rendered, authenticated with a client assertion")
    void shouldPushExactlyTheEngineBuiltParameterSet(URIBuilder uriBuilder) {
        ClientConfiguration configuration = configuration();
        ProviderMetadata metadata = metadata(parEndpoint(uriBuilder));
        FlowContext context = FlowContext.create(REDIRECT_URI);
        String authorizationUrl = REQUEST_BUILDER.build(configuration, metadata, context);

        adapter(configuration).push(metadata, CLIENT_ID, authorizationUrl);

        assertEquals(1, received.size(), "exactly one request is pushed");
        Pushed pushed = received.getFirst();
        Map<String, String> form = pushed.form();
        assertAll("the pushed request of a login",
                () -> assertEquals(engineParameters(authorizationUrl), withoutClientAuthentication(form),
                        "apart from the client authentication, the body is the engine-built parameter set"),
                () -> assertEquals(Set.copyOf(SCOPES), scopeSet(form.get("scope")), "the requested scope set"),
                () -> assertEquals("query", form.get("response_mode")),
                () -> assertEquals("S256", form.get("code_challenge_method")),
                () -> assertEquals("code", form.get("response_type")),
                () -> assertEquals(CLIENT_ID, form.get(PARAM_CLIENT_ID)),
                () -> assertEquals(context.state(), form.get(PARAM_STATE)),
                () -> assertEquals(context.nonce(), form.get(PARAM_NONCE)),
                () -> assertEquals(context.pkceChallenge().codeChallenge(), form.get(PARAM_CODE_CHALLENGE)),
                () -> assertEquals(REDIRECT_URI, form.get("redirect_uri")),
                () -> assertFalse(form.containsKey("acr_values"), "a login requests no authentication context"),
                () -> assertFalse(form.containsKey("max_age"), "nor an authentication age"),
                () -> assertEquals(JWT_BEARER_ASSERTION, form.get(CLIENT_ASSERTION_TYPE)),
                () -> assertEquals(3, String.valueOf(form.get(CLIENT_ASSERTION)).split("\\.").length,
                        "the client assertion is a compact JWS"),
                () -> assertFalse(form.containsKey("dpop_jkt"), "the gateway adds no dpop_jkt"),
                () -> assertEquals(Optional.empty(), pushed.header(DPOP_HEADER), "and the push carries no proof"),
                () -> assertEquals(Optional.empty(), pushed.header(AUTHORIZATION),
                        "key authentication sends no Authorization header"));
    }

    @Test
    @DisplayName("Should additionally push acr_values and max_age for a step-up request")
    void shouldPushTheElevatedParametersOfAStepUpRequest(URIBuilder uriBuilder) {
        ClientConfiguration configuration = configuration();
        ProviderMetadata metadata = metadata(parEndpoint(uriBuilder));
        FlowContext context = FlowContext.create(REDIRECT_URI, STEP_UP_ACR, STEP_UP_MAX_AGE);
        String authorizationUrl = REQUEST_BUILDER.build(configuration, metadata, context);

        String redirect = adapter(configuration).push(metadata, CLIENT_ID, authorizationUrl);

        assertEquals(1, received.size(), "exactly one request is pushed");
        Map<String, String> form = received.getFirst().form();
        assertAll("the pushed request of a step-up re-drive",
                () -> assertEquals(engineParameters(authorizationUrl), withoutClientAuthentication(form),
                        "the body is the engine-built parameter set of the step-up request"),
                () -> assertEquals(STEP_UP_ACR, form.get("acr_values")),
                () -> assertEquals(Integer.toString(STEP_UP_MAX_AGE), form.get("max_age")),
                () -> assertEquals(context.state(), form.get(PARAM_STATE)),
                () -> assertEquals(List.of(PARAM_CLIENT_ID, PARAM_REQUEST_URI),
                        List.copyOf(queryOf(redirect).keySet()),
                        "the elevated parameters travel in the pushed request, never in the redirect"));
    }

    @Test
    @DisplayName("Should return the authorization endpoint with exactly client_id and request_uri, form-encoded")
    void shouldReturnTheAuthorizationEndpointWithClientIdAndRequestUri(URIBuilder uriBuilder) {
        ClientConfiguration configuration = configuration();
        ProviderMetadata metadata = metadata(parEndpoint(uriBuilder));
        String requestUri = REQUEST_URI_PREFIX + Generators.letterStrings(16, 24).next();
        scripted.add(accepted(requestUri));
        String authorizationUrl = REQUEST_BUILDER.build(configuration, metadata, FlowContext.create(REDIRECT_URI));

        String redirect = adapter(configuration).push(metadata, CLIENT_ID, authorizationUrl);

        Map<String, String> query = queryOf(redirect);
        assertAll("the redirect the browser is sent to",
                () -> assertEquals(AUTHORIZATION_ENDPOINT + "?" + PARAM_CLIENT_ID + "="
                        + URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8) + "&" + PARAM_REQUEST_URI + "="
                        + URLEncoder.encode(requestUri, StandardCharsets.UTF_8), redirect,
                        "the authorization endpoint, client_id, request_uri — both values form-encoded"),
                () -> assertEquals(List.of(PARAM_CLIENT_ID, PARAM_REQUEST_URI), List.copyOf(query.keySet()),
                        "exactly two parameters"),
                () -> assertEquals(CLIENT_ID, query.get(PARAM_CLIENT_ID)),
                () -> assertEquals(requestUri, query.get(PARAM_REQUEST_URI),
                        "the request_uri is the one the identity provider answered"));
    }

    @Test
    @DisplayName("Should keep the query an authorization endpoint carries, and push none of it")
    void shouldKeepTheQueryOfTheAuthorizationEndpoint(URIBuilder uriBuilder) {
        ClientConfiguration configuration = configuration();
        ProviderMetadata metadata = metadata(parEndpoint(uriBuilder));
        metadata.authorizationEndpoint = AUTHORIZATION_ENDPOINT + "?tenant=blue";
        String authorizationUrl = REQUEST_BUILDER.build(configuration, metadata, FlowContext.create(REDIRECT_URI));

        String redirect = adapter(configuration).push(metadata, CLIENT_ID, authorizationUrl);

        assertEquals(1, received.size(), "exactly one request is pushed");
        assertAll("an authorization endpoint with a query of its own",
                () -> assertEquals(List.of("tenant", PARAM_CLIENT_ID, PARAM_REQUEST_URI),
                        List.copyOf(queryOf(redirect).keySet()),
                        "the endpoint's own parameter stays, followed by the two the adapter adds"),
                () -> assertFalse(received.getFirst().form().containsKey("tenant"),
                        "the endpoint's own parameter is no authorization parameter and is not pushed"));
    }

    @ParameterizedTest(name = "pushed_authorization_request_endpoint ''{0}''")
    @ValueSource(strings = {"", "  "})
    @DisplayName("Should refuse a provider whose metadata names a blank pushed-authorization-request endpoint")
    void shouldRefuseABlankPushedAuthorizationRequestEndpoint(String blank) {
        ClientConfiguration configuration = configuration();
        ProviderMetadata metadata = metadata(blank);
        String authorizationUrl = REQUEST_BUILDER.build(configuration, metadata, FlowContext.create(REDIRECT_URI));
        PushedAuthorizationRequests adapter = adapter(configuration);

        GatewayException refused = assertThrows(GatewayException.class,
                () -> adapter.push(metadata, CLIENT_ID, authorizationUrl));

        assertRefusedWithoutARequest(refused, REASON_NO_PAR_ENDPOINT);
    }

    @Test
    @DisplayName("Should refuse a provider that advertises no pushed-authorization-request endpoint, sending nothing")
    void shouldRefuseAProviderWithoutAPushedAuthorizationRequestEndpoint() {
        ClientConfiguration configuration = configuration();
        ProviderMetadata metadata = metadata(null);
        String authorizationUrl = REQUEST_BUILDER.build(configuration, metadata, FlowContext.create(REDIRECT_URI));
        PushedAuthorizationRequests adapter = adapter(configuration);

        GatewayException refused = assertThrows(GatewayException.class,
                () -> adapter.push(metadata, CLIENT_ID, authorizationUrl));

        assertRefusedWithoutARequest(refused, REASON_NO_PAR_ENDPOINT);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(PushFailure.class)
    @DisplayName("Should refuse the login with the 502 event when the push fails")
    void shouldRefuseAFailedPush(PushFailure failure, URIBuilder uriBuilder) {
        ClientConfiguration configuration = configuration(READ_TIMEOUT_SECONDS);
        ProviderMetadata metadata = metadata(parEndpoint(uriBuilder));
        String authorizationUrl = REQUEST_BUILDER.build(configuration, metadata, FlowContext.create(REDIRECT_URI));
        PushedAuthorizationRequests adapter = adapter(configuration);
        scripted.add(failure.answer());

        GatewayException refused = assertThrows(GatewayException.class,
                () -> adapter.push(metadata, CLIENT_ID, authorizationUrl), failure.name());

        assertAll("a push that failed: " + failure,
                () -> assertRefusal(refused, REASON_PUSH_FAILED),
                () -> assertInstanceOf(TokenSheriffException.class, refused.getCause(),
                        "the engine failure is kept as the cause"),
                () -> assertEquals(1, received.size(), "the request did reach the endpoint — the refusal is of its answer"));
    }

    @Test
    @DisplayName("Should refuse the login with the 502 event when the endpoint cannot be dialled")
    void shouldRefuseAPushThatCannotBeDialled() {
        // A plain-HTTP endpoint under a configuration that does not allow it is refused by the engine
        // before anything is sent — a transport failure that needs no listener and no free port.
        ClientConfiguration strict = ClientConfiguration.builder().issuer(ISSUER).clientId(CLIENT_ID)
                .authMethod(ClientAuthMethod.PRIVATE_KEY_JWT).scopes(SCOPES).redirectUri(REDIRECT_URI).build();
        ProviderMetadata metadata = metadata("http://idp.example.com/par");
        String authorizationUrl = REQUEST_BUILDER.build(strict, metadata, FlowContext.create(REDIRECT_URI));
        PushedAuthorizationRequests adapter = adapter(strict);

        GatewayException refused = assertThrows(GatewayException.class,
                () -> adapter.push(metadata, CLIENT_ID, authorizationUrl));

        assertAll("a push the engine refuses to dial",
                () -> assertRefusal(refused, REASON_PUSH_FAILED),
                () -> assertEquals(List.of(), received, "nothing was sent"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(InvalidRequest.class)
    @DisplayName("Should refuse an authorization URL that is no uniquely named parameter set, sending nothing")
    void shouldRefuseAnInvalidAuthorizationUrl(InvalidRequest invalid, URIBuilder uriBuilder) {
        ClientConfiguration configuration = configuration();
        ProviderMetadata metadata = metadata(parEndpoint(uriBuilder));
        String authorizationUrl = invalid.corrupt(
                REQUEST_BUILDER.build(configuration, metadata, FlowContext.create(REDIRECT_URI)));
        PushedAuthorizationRequests adapter = adapter(configuration);

        GatewayException refused = assertThrows(GatewayException.class,
                () -> adapter.push(metadata, CLIENT_ID, authorizationUrl), invalid.name());

        assertRefusedWithoutARequest(refused, REASON_INVALID_REQUEST);
    }

    @Test
    @DisplayName("Should refuse a provider whose metadata names no authorization endpoint, sending nothing")
    void shouldRefuseMetadataWithoutAnAuthorizationEndpoint(URIBuilder uriBuilder) {
        ClientConfiguration configuration = configuration();
        ProviderMetadata metadata = metadata(parEndpoint(uriBuilder));
        String authorizationUrl = REQUEST_BUILDER.build(configuration, metadata, FlowContext.create(REDIRECT_URI));
        metadata.authorizationEndpoint = null;
        PushedAuthorizationRequests adapter = adapter(configuration);

        GatewayException refused = assertThrows(GatewayException.class,
                () -> adapter.push(metadata, CLIENT_ID, authorizationUrl));

        assertRefusedWithoutARequest(refused, REASON_INVALID_REQUEST);
    }

    @Test
    @DisplayName("Should record a refusal as WARN once per reason and as DEBUG on every repetition")
    void shouldLatchTheRefusalRecordPerReason(URIBuilder uriBuilder) {
        ClientConfiguration configuration = configuration();
        ProviderMetadata withoutEndpoint = metadata(null);
        ProviderMetadata withEndpoint = metadata(parEndpoint(uriBuilder));
        String authorizationUrl =
                REQUEST_BUILDER.build(configuration, withEndpoint, FlowContext.create(REDIRECT_URI));
        String repeatedParameter = InvalidRequest.REPEATED_PARAMETER_NAME.corrupt(authorizationUrl);
        PushedAuthorizationRequests adapter = adapter(configuration);

        assertThrows(GatewayException.class, () -> adapter.push(withoutEndpoint, CLIENT_ID, authorizationUrl));
        int warnsAfterTheFirst = refusalRecords().size();
        assertThrows(GatewayException.class, () -> adapter.push(withoutEndpoint, CLIENT_ID, authorizationUrl));
        assertThrows(GatewayException.class, () -> adapter.push(withoutEndpoint, CLIENT_ID, authorizationUrl));
        int warnsAfterTheRepetitions = refusalRecords().size();
        assertThrows(GatewayException.class, () -> adapter.push(withEndpoint, CLIENT_ID, repeatedParameter));

        List<String> warns = refusalRecords().stream().map(captured -> String.valueOf(captured.getMessage())).toList();
        assertAll("the refusal record is latched per reason",
                () -> assertEquals(1, warnsAfterTheFirst, "the first refusal of a reason is a WARN"),
                () -> assertEquals(1, warnsAfterTheRepetitions, "its repetitions add no WARN"),
                () -> assertEquals(2, repetitionRecords(REASON_NO_PAR_ENDPOINT),
                        "each repetition is one DEBUG record naming the reason"),
                () -> assertEquals(2, warns.size(), "another reason is recorded with its own WARN: " + warns),
                () -> assertTrue(warns.getFirst().contains(REASON_NO_PAR_ENDPOINT), warns.getFirst()),
                () -> assertTrue(warns.getLast().contains(REASON_INVALID_REQUEST), warns.getLast()),
                () -> assertEquals(0, repetitionRecords(REASON_INVALID_REQUEST),
                        "the first refusal of the second reason is no repetition"));
    }

    @Test
    @DisplayName("Should hold the latch per instance, so a second adapter reports the same reason again")
    void shouldHoldTheLatchPerInstance() {
        ClientConfiguration configuration = configuration();
        ProviderMetadata withoutEndpoint = metadata(null);
        String authorizationUrl =
                REQUEST_BUILDER.build(configuration, withoutEndpoint, FlowContext.create(REDIRECT_URI));
        PushedAuthorizationRequests first = adapter(configuration);
        PushedAuthorizationRequests second = adapter(configuration);

        assertThrows(GatewayException.class, () -> first.push(withoutEndpoint, CLIENT_ID, authorizationUrl));
        assertThrows(GatewayException.class, () -> second.push(withoutEndpoint, CLIENT_ID, authorizationUrl));

        assertEquals(2, refusalRecords().size(), "each adapter records the reason once");
    }

    /**
     * The step-up re-drive pushes through the same adapter instance as the login, and the adapter is
     * not told which of the two it serves. The record therefore names the consequence both share and
     * neither caller: the same text is recorded for a refused login request and a refused step-up
     * request.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(PushedRequest.class)
    @DisplayName("Should record a refused push in one text for a login and a step-up request, naming neither")
    void shouldRecordARefusedPushWithoutNamingTheCaller(PushedRequest kind) {
        ClientConfiguration configuration = configuration();
        ProviderMetadata metadata = metadata(null);
        String authorizationUrl = REQUEST_BUILDER.build(configuration, metadata, kind.context());
        PushedAuthorizationRequests adapter = adapter(configuration);

        GatewayException refused = assertThrows(GatewayException.class,
                () -> adapter.push(metadata, CLIENT_ID, authorizationUrl));

        assertRefusedWithoutARequest(refused, REASON_NO_PAR_ENDPOINT);
        String recorded = String.valueOf(refusalRecords().getFirst().getMessage());
        assertTrue(recorded.endsWith("Pushed authorization request refused: " + REASON_NO_PAR_ENDPOINT
                        + " — no redirect to the identity provider was issued; further refusals with this reason"
                        + " stay at DEBUG"),
                "the record states what holds for a login and for a step-up re-drive alike: " + recorded);
    }

    @Test
    @DisplayName("Should disclose neither state, nonce, PKCE challenge and client assertion nor the request_uri")
    void shouldDiscloseNeitherStateNonceChallengeAndAssertionNorTheRequestUri(URIBuilder uriBuilder) {
        ClientConfiguration configuration = configuration(READ_TIMEOUT_SECONDS);
        ProviderMetadata metadata = metadata(parEndpoint(uriBuilder));
        FlowContext refusedContext = FlowContext.create(REDIRECT_URI);
        FlowContext pushedContext = FlowContext.create(REDIRECT_URI);
        String refusedUrl = REQUEST_BUILDER.build(configuration, metadata, refusedContext);
        String pushedUrl = REQUEST_BUILDER.build(configuration, metadata, pushedContext);
        String requestUri = REQUEST_URI_PREFIX + Generators.letterStrings(16, 24).next();
        PushedAuthorizationRequests adapter = adapter(configuration);
        scripted.add(PushFailure.SERVER_ERROR.answer());
        scripted.add(accepted(requestUri));

        GatewayException failed = assertThrows(GatewayException.class,
                () -> adapter.push(metadata, CLIENT_ID, refusedUrl));
        GatewayException invalid = assertThrows(GatewayException.class,
                () -> adapter.push(metadata, CLIENT_ID, InvalidRequest.UNDECODABLE_PAIR.corrupt(refusedUrl)));
        String redirect = adapter.push(metadata, CLIENT_ID, pushedUrl);

        assertEquals(requestUri, queryOf(redirect).get(PARAM_REQUEST_URI), "the second push was accepted");
        List<String> secrets = new ArrayList<>(List.of(requestUri, refusedUrl, pushedUrl));
        for (FlowContext context : List.of(refusedContext, pushedContext)) {
            secrets.addAll(List.of(context.state(), context.nonce(), context.pkceChallenge().codeChallenge()));
        }
        received.forEach(pushed -> secrets.add(pushed.form().get(CLIENT_ASSERTION)));
        assertNothingIsDisclosed(secrets, failed, invalid);
    }

    // The adapter with the engine's client_secret_basic authentication in place of the key-based one.
    // It is handed an authentication and never looks at it, so the pushed parameter set and the
    // redirect are the ones of the tests above; only how the request is authenticated differs.

    @ParameterizedTest(name = "{0}")
    @EnumSource(PushedRequest.class)
    @DisplayName("Should push the same parameter set with a client secret, authenticated by the Basic header alone")
    void shouldPushTheSameParameterSetWithAClientSecret(PushedRequest kind, URIBuilder uriBuilder) {
        String secret = clientSecret();
        ClientConfiguration configuration = secretConfiguration(secret);
        ProviderMetadata metadata = metadata(parEndpoint(uriBuilder));
        FlowContext context = kind.context();
        String authorizationUrl = REQUEST_BUILDER.build(configuration, metadata, context);

        secretAdapter(configuration, secret).push(metadata, CLIENT_ID, authorizationUrl);

        assertEquals(1, received.size(), "exactly one request is pushed");
        Pushed pushed = received.getFirst();
        Map<String, String> form = pushed.form();
        assertAll("the pushed request of a " + kind + " under client-secret authentication",
                () -> assertEquals(engineParameters(authorizationUrl), form,
                        "the body is the engine-built parameter set exactly — the authentication adds no parameter"),
                () -> assertEquals(Set.copyOf(SCOPES), scopeSet(form.get("scope")), "the requested scope set"),
                () -> assertEquals("query", form.get("response_mode")),
                () -> assertEquals(context.state(), form.get(PARAM_STATE)),
                () -> assertEquals(context.acrValues(), Optional.ofNullable(form.get("acr_values")),
                        "the elevated authentication context is pushed exactly when the request carries one"),
                () -> assertFalse(form.containsKey(CLIENT_ASSERTION), "no client_assertion is sent"),
                () -> assertFalse(form.containsKey(CLIENT_ASSERTION_TYPE), "no client_assertion_type is sent"),
                () -> assertFalse(form.containsKey("client_secret"),
                        "the secret travels in the header only, never as a form parameter"),
                () -> assertFalse(form.containsKey("dpop_jkt"), "the gateway adds no dpop_jkt"),
                () -> assertEquals(Optional.of(basicCredential(secret)), pushed.header(AUTHORIZATION),
                        "the request carries the form-encoded client id and secret as the Basic credential"),
                () -> assertEquals(Optional.empty(), pushed.header(DPOP_HEADER), "and no proof"));
    }

    @Test
    @DisplayName("Should return the same two-parameter redirect with a client secret, disclosing the secret nowhere")
    void shouldReturnTheSameRedirectWithAClientSecret(URIBuilder uriBuilder) {
        String secret = clientSecret();
        ClientConfiguration configuration = secretConfiguration(secret);
        ProviderMetadata metadata = metadata(parEndpoint(uriBuilder));
        String requestUri = REQUEST_URI_PREFIX + Generators.letterStrings(16, 24).next();
        scripted.add(accepted(requestUri));
        scripted.add(PushFailure.SERVER_ERROR.answer());
        String authorizationUrl = REQUEST_BUILDER.build(configuration, metadata, FlowContext.create(REDIRECT_URI));
        PushedAuthorizationRequests adapter = secretAdapter(configuration, secret);

        String redirect = adapter.push(metadata, CLIENT_ID, authorizationUrl);
        GatewayException refused = assertThrows(GatewayException.class,
                () -> adapter.push(metadata, CLIENT_ID, authorizationUrl));

        assertEquals(AUTHORIZATION_ENDPOINT + "?" + PARAM_CLIENT_ID + "="
                + URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8) + "&" + PARAM_REQUEST_URI + "="
                + URLEncoder.encode(requestUri, StandardCharsets.UTF_8), redirect,
                "the authorization endpoint with client_id and request_uri, and nothing else");
        assertRefusal(refused, REASON_PUSH_FAILED);
        assertNothingIsDisclosed(List.of(secret, URLEncoder.encode(secret, StandardCharsets.UTF_8),
                basicCredential(secret).substring(BASIC_SCHEME.length())), refused);
    }

    @Test
    @DisplayName("Should reject absent collaborators and absent arguments")
    void shouldRejectAbsentArguments() {
        ClientConfiguration configuration = configuration();
        ParClient parClient = new ParClient(configuration);
        ProviderMetadata metadata = metadata(null);
        PushedAuthorizationRequests adapter = adapter(configuration);

        assertAll("the null contract",
                () -> assertThrows(NullPointerException.class,
                        () -> new PushedAuthorizationRequests(null, keyAuthentication)),
                () -> assertThrows(NullPointerException.class, () -> new PushedAuthorizationRequests(parClient, null)),
                () -> assertThrows(NullPointerException.class,
                        () -> adapter.push(null, CLIENT_ID, AUTHORIZATION_ENDPOINT)),
                () -> assertThrows(NullPointerException.class,
                        () -> adapter.push(metadata, null, AUTHORIZATION_ENDPOINT)),
                () -> assertThrows(NullPointerException.class, () -> adapter.push(metadata, CLIENT_ID, null)));
    }

    /** Asserts a refusal that was decided before anything was sent. */
    private void assertRefusedWithoutARequest(GatewayException refused, String reason) {
        assertAll("a refusal decided before the push",
                () -> assertRefusal(refused, reason),
                () -> assertNull(refused.getCause(), "nothing was sent, so there is no engine failure to chain"),
                () -> assertEquals(List.of(), received, "the endpoint received no request"));
    }

    /**
     * Asserts the refusal contract: the {@code 502} event, a message that names the reason, and the
     * record {@code ApiSheriff-132} naming the same reason.
     */
    private static void assertRefusal(GatewayException refused, String reason) {
        List<String> recorded = refusalRecords().stream()
                .map(captured -> String.valueOf(captured.getMessage())).toList();
        assertAll("the refusal of a pushed authorization request",
                () -> assertEquals(EventType.UPSTREAM_ERROR, refused.getEventType()),
                () -> assertEquals(502, refused.getEventType().httpStatus(), "the edge answers 502"),
                () -> assertEquals("Pushed authorization request refused (" + reason + ")", refused.getMessage(),
                        "the message names the reason token and nothing else"),
                () -> assertEquals(1, recorded.size(), "the refusal is recorded once: " + recorded),
                () -> assertTrue(recorded.getFirst().contains("refused: " + reason + " "),
                        "the record names the reason: " + recorded.getFirst()));
    }

    /**
     * Asserts that no captured record, down to {@code DEBUG}, and no message of a refusal or of a
     * cause it chains carries one of {@code secrets}.
     * <p>
     * The control comes first: a {@code DEBUG} record of the adapter and of the engine client it
     * pushes through is captured, so the records read below include the {@code DEBUG} output of both.
     */
    private static void assertNothingIsDisclosed(List<String> secrets, Throwable... refusals) {
        SheriffDebugCapture.assertDebugIsCaptured(PushedAuthorizationRequests.class, ParClient.class);
        List<LogRecord> records = TestLoggerFactory.getTestHandler().getRecords();
        assertFalse(records.isEmpty(), "no record was captured at all, so the absence would prove nothing");
        List<Executable> checks = new ArrayList<>();
        for (LogRecord captured : records) {
            String rendered = SheriffDebugCapture.rendered(captured);
            checks.add(() -> assertTrue(secrets.stream().noneMatch(rendered::contains),
                    "a " + captured.getLevel() + " record of " + captured.getLoggerName()
                            + " carries a value of the authorization request"));
        }
        for (Throwable refusal : refusals) {
            for (Throwable cause = refusal; cause != null; cause = cause.getCause()) {
                String message = String.valueOf(cause.getMessage());
                checks.add(() -> assertTrue(secrets.stream().noneMatch(message::contains),
                        "a refusal carries a value of the authorization request: " + message));
            }
        }
        assertAll("nothing of the authorization request is disclosed", checks);
    }

    private static List<LogRecord> refusalRecords() {
        return TestLoggerFactory.getTestHandler().resolveLogMessagesContaining(TestLogLevel.WARN,
                BffLogMessages.WARN.AUTHORIZATION_PUSH_REFUSED.resolveIdentifierString());
    }

    /** The number of DEBUG records that report a repetition of {@code reason}. */
    private static int repetitionRecords(String reason) {
        return TestLoggerFactory.getTestHandler()
                .resolveLogMessagesContaining(TestLogLevel.DEBUG, "refused again (" + reason + ")").size();
    }

    private PushedAuthorizationRequests adapter(ClientConfiguration configuration) {
        return new PushedAuthorizationRequests(new ParClient(configuration), keyAuthentication);
    }

    private static PushedAuthorizationRequests secretAdapter(ClientConfiguration configuration, String secret) {
        return new PushedAuthorizationRequests(new ParClient(configuration),
                new ClientSecretBasicAuth(CLIENT_ID, secret));
    }

    /** A client secret carrying characters the form encoding has to escape. */
    private static String clientSecret() {
        return Generators.letterStrings(16, 32).next() + "+/ :&=%" + Generators.letterStrings(4, 8).next();
    }

    /** The {@code Authorization} header value RFC 6749 section 2.3.1 renders for the client id and {@code secret}. */
    private static String basicCredential(String secret) {
        String credential = URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8) + ":"
                + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        return BASIC_SCHEME + Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8));
    }

    /** The client configuration of a client that authenticates with {@code secret}; plain HTTP is allowed. */
    private static ClientConfiguration secretConfiguration(String secret) {
        return ClientConfiguration.builder()
                .issuer(ISSUER).clientId(CLIENT_ID).clientSecret(secret)
                .authMethod(ClientAuthMethod.CLIENT_SECRET_BASIC)
                .scopes(SCOPES).redirectUri(REDIRECT_URI)
                .allowInsecureHttp(true)
                .build();
    }

    private static ClientConfiguration configuration() {
        return configuration(ClientConfiguration.DEFAULT_READ_TIMEOUT_SECONDS);
    }

    /** The client configuration of this test: plain HTTP is allowed, because the endpoint is in-process. */
    private static ClientConfiguration configuration(int readTimeoutSeconds) {
        return ClientConfiguration.builder()
                .issuer(ISSUER).clientId(CLIENT_ID)
                .authMethod(ClientAuthMethod.PRIVATE_KEY_JWT)
                .scopes(SCOPES).redirectUri(REDIRECT_URI)
                .allowInsecureHttp(true)
                .readTimeoutSeconds(readTimeoutSeconds)
                .build();
    }

    private static String parEndpoint(URIBuilder uriBuilder) {
        return uriBuilder.addPathSegment(PAR_SEGMENT).buildAsString();
    }

    /**
     * Provider metadata naming the authorization endpoint and PKCE {@code S256}, and
     * {@code parEndpoint} as the pushed-authorization-request endpoint — {@code null} for a provider
     * that advertises none.
     */
    private static ProviderMetadata metadata(String parEndpoint) {
        ProviderMetadata metadata = new ProviderMetadata();
        metadata.issuer = ISSUER;
        metadata.authorizationEndpoint = AUTHORIZATION_ENDPOINT;
        metadata.pushedAuthorizationRequestEndpoint = parEndpoint;
        metadata.codeChallengeMethodsSupported = List.of(ProviderMetadata.CODE_CHALLENGE_METHOD_S256);
        return metadata;
    }

    /** A {@code 201} answer of the endpoint carrying {@code requestUri}. */
    private static MockResponse accepted(String requestUri) {
        return json(201, "{\"request_uri\":\"" + requestUri + "\",\"expires_in\":60}");
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body)
                .build();
    }

    /** The parameters the engine rendered into an authorization URL built on the authorization endpoint. */
    private static Map<String, String> engineParameters(String authorizationUrl) {
        return formPairs(authorizationUrl.substring(AUTHORIZATION_ENDPOINT.length() + 1));
    }

    /** The decoded query of a redirect on the authorization endpoint, in URL order. */
    private static Map<String, String> queryOf(String redirect) {
        assertTrue(redirect.startsWith(AUTHORIZATION_ENDPOINT + "?"),
                "the redirect is built on the authorization endpoint: " + redirect);
        return formPairs(redirect.substring(AUTHORIZATION_ENDPOINT.length() + 1));
    }

    /** A pushed form body without the two parameters the client authentication adds. */
    private static Map<String, String> withoutClientAuthentication(Map<String, String> form) {
        Map<String, String> parameters = new LinkedHashMap<>(form);
        parameters.remove(CLIENT_ASSERTION_TYPE);
        parameters.remove(CLIENT_ASSERTION);
        return parameters;
    }

    /**
     * Decodes a form-encoded string — a query or a request body — into its pairs, in order, refusing
     * a name that occurs twice.
     */
    private static Map<String, String> formPairs(String encoded) {
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

    private static Set<String> scopeSet(String scopeParameter) {
        return Arrays.stream(scopeParameter.split(" ")).collect(Collectors.toSet());
    }
}
