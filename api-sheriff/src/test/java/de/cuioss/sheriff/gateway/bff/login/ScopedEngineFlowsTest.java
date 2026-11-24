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

import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.LogRecord;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.sheriff.gateway.bff.client.TestSigningKeys;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.testsupport.SheriffDebugCapture;
import de.cuioss.sheriff.token.client.auth.ClientAuthentication;
import de.cuioss.sheriff.token.client.auth.ClientSecretBasicAuth;
import de.cuioss.sheriff.token.client.config.ClientAuthMethod;
import de.cuioss.sheriff.token.client.config.ClientConfiguration;
import de.cuioss.sheriff.token.client.discovery.ProviderMetadata;
import de.cuioss.sheriff.token.client.dpop.DpopProofGenerator;
import de.cuioss.sheriff.token.client.dpop.SenderConstraint;
import de.cuioss.sheriff.token.client.flow.AuthorizationCodeFlow;
import de.cuioss.sheriff.token.client.flow.AuthorizationRequestOptions;
import de.cuioss.sheriff.token.client.flow.FlowContext;
import de.cuioss.sheriff.token.client.flow.IssValidator;
import de.cuioss.sheriff.token.client.flow.ParClient;
import de.cuioss.sheriff.token.client.flow.RefreshFlow;
import de.cuioss.sheriff.token.client.flow.ResponseMode;
import de.cuioss.sheriff.token.client.flow.StepUpChallengeParser.StepUpChallenge;
import de.cuioss.sheriff.token.client.flow.TokenEndpointClient;
import de.cuioss.sheriff.token.client.token.IdTokenValidationBridge;
import de.cuioss.sheriff.token.client.token.TokenValidationBridge;
import de.cuioss.sheriff.token.commons.error.TransportException;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.sheriff.token.validation.util.JwkThumbprintUtil;
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
 * Tests for {@link ScopedEngineFlows}: the one engine flow pushes every authorization request with the
 * scope set, {@code response_mode} and {@code prompt} named per call, the browser location carries
 * {@code client_id} and {@code request_uri} and nothing else, the refresh grant carries the canonical
 * scope set, and every failure to push refuses the request with the {@code 502} event, recorded once
 * per reason.
 * <p>
 * Each consumer-visible element of an authorization request is pinned here once per leg — the login,
 * the silent and the interactive widening, and the RFC 9470 step-up re-drive, which this seam still
 * pushes itself. The seam is driven with the engine's own collaborators: a real
 * {@link AuthorizationCodeFlow}, a real {@link RefreshFlow} and a real {@link ParClient}, against an
 * in-process identity provider whose pushed-authorization-request endpoint records each push and
 * answers from a script a test may fill, and whose token endpoint records each grant and refuses it.
 * The endpoints are plain HTTP, which the hand-built configuration allows; the TLS posture of the
 * back channel is proven on a produced runtime in {@code BffRuntimeProducerTest}.
 * <p>
 * Every assertion on what a push carries is paired with the matched control that makes it falsifiable:
 * a name asserted absent from the browser location is asserted present in the push, a {@code prompt}
 * asserted on the silent attempt is asserted absent on the interactive one, and every refusal has an
 * accepted counterpart over the same fixture.
 * <p>
 * The tests sit at the top level on purpose: the mock server resolves its dispatcher from the test
 * instance, and a nested instance does not declare one.
 */
@EnableGeneratorController
@EnableMockWebServer
@ModuleDispatcher
@EnableTestLogger(rootLevel = TestLogLevel.DEBUG)
@ExtendWith(SheriffDebugCapture.class)
@DisplayName("ScopedEngineFlows — one engine flow, every authorization request pushed with per-call options")
class ScopedEngineFlowsTest {

    private static final String ISSUER = "https://idp.example.com";
    private static final String AUTHORIZATION_ENDPOINT = ISSUER + "/authorize";
    private static final String REDIRECT_URI = "https://gw.example.com/auth/callback";
    private static final String PAR_PATH = "/par";
    private static final String TOKEN_PATH = "/token";
    /** Carries characters the form encoding has to escape, so an unencoded location cannot pass. */
    private static final String CLIENT_ID = "gateway client:1&x=y";
    private static final String CLIENT_SECRET = "gateway-secret";
    private static final String REQUEST_URI_PREFIX = "urn:ietf:params:oauth:request_uri:";
    private static final String DPOP_HEADER = "DPoP";
    private static final String AUTHORIZATION = "Authorization";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> SCOPED = List.of("openid", "profile", "email", "orders:read");
    /** {@link #SCOPED} out of order and with a duplicate — the canonical form is the same. */
    private static final List<String> SCOPED_UNORDERED = List.of("orders:read", "openid", "email", "profile",
            "openid");
    /** The canonical {@code scope} value of {@link #SCOPED}: deduplicated, sorted, space-separated. */
    private static final String SCOPED_CANONICAL = "email openid orders:read profile";
    private static final String STEP_UP_ACR = "urn:example:gold";
    private static final int STEP_UP_MAX_AGE = 300;
    private static final String PARAM_CLIENT_ID = "client_id";
    private static final String PARAM_REQUEST_URI = "request_uri";
    private static final String PARAM_SCOPE = "scope";
    private static final String PARAM_STATE = "state";
    private static final String PARAM_NONCE = "nonce";
    private static final String PARAM_CODE_CHALLENGE = "code_challenge";
    private static final String PARAM_RESPONSE_MODE = "response_mode";
    private static final String PARAM_PROMPT = "prompt";
    private static final String REASON_NO_PAR_ENDPOINT = "no-par-endpoint";
    private static final String REASON_INVALID_REQUEST = "invalid-request";
    private static final String REASON_PUSH_FAILED = "push-failed";
    /**
     * Every authorization parameter an engine-built request carries. None of them may reach the browser
     * location of a pushed request; each of them is in the push.
     */
    private static final List<String> AUTHORIZATION_PARAMETERS = List.of("response_type", "redirect_uri",
            PARAM_SCOPE, PARAM_STATE, PARAM_NONCE, PARAM_CODE_CHALLENGE, "code_challenge_method",
            PARAM_RESPONSE_MODE);

    /** The four legs that start an authorization request through the seam. */
    enum Leg {

        /** The login. */
        LOGIN {
            @Override
            Started start(ScopedEngineFlows flows, ProviderMetadata metadata, List<String> scopes) {
                AuthorizationCodeFlow.AuthorizationRedirect redirect = flows.authorize(metadata, scopes);
                return new Started(redirect.authorizationUrl(), redirect.context());
            }
        },

        /** The silent ({@code prompt=none}) widening attempt. */
        SILENT_WIDENING {
            @Override
            Started start(ScopedEngineFlows flows, ProviderMetadata metadata, List<String> scopes) {
                AuthorizationCodeFlow.AuthorizationRedirect redirect = flows.widen(metadata, scopes, true);
                return new Started(redirect.authorizationUrl(), redirect.context());
            }
        },

        /** The interactive widening attempt. */
        INTERACTIVE_WIDENING {
            @Override
            Started start(ScopedEngineFlows flows, ProviderMetadata metadata, List<String> scopes) {
                AuthorizationCodeFlow.AuthorizationRedirect redirect = flows.widen(metadata, scopes, false);
                return new Started(redirect.authorizationUrl(), redirect.context());
            }
        },

        /** The RFC 9470 step-up re-drive, pushed by the seam itself. */
        STEP_UP {
            @Override
            Started start(ScopedEngineFlows flows, ProviderMetadata metadata, List<String> scopes) {
                var request = flows.stepUp(metadata, new StepUpChallenge(STEP_UP_ACR, STEP_UP_MAX_AGE), scopes);
                return new Started(request.authorizationUrl(), request.context());
            }
        };

        abstract Started start(ScopedEngineFlows flows, ProviderMetadata metadata, List<String> scopes);
    }

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

        /** The endpoint answers {@code 201} with a body that names no {@code request_uri}. */
        SUCCESS_WITHOUT_REQUEST_URI {
            @Override
            MockResponse answer() {
                return json(201, "{\"expires_in\":60}");
            }
        },

        /** The endpoint answers {@code 201} with a JSON body that does not parse. */
        UNPARSEABLE_JSON_BODY {
            @Override
            MockResponse answer() {
                return json(201, "{\"request_uri\":");
            }
        },

        /** The endpoint answers {@code 201} with a body that is not JSON at all. */
        NON_JSON_BODY {
            @Override
            MockResponse answer() {
                return new MockResponse.Builder().code(201).addHeader("Content-Type", "text/html")
                        .body("<html>not a pushed authorization response</html>").build();
            }
        };

        abstract MockResponse answer();
    }

    /**
     * What a leg started: the browser location and the transaction context to persist.
     *
     * @param location the authorization URL the browser is sent to
     * @param context  the engine transaction context
     */
    record Started(String location, FlowContext context) {
    }

    /**
     * One request the identity provider received.
     *
     * @param headers the request headers
     * @param body    the form-encoded request body
     */
    private record Received(Headers headers, String body) {

        Map<String, String> form() {
            return formPairs(body);
        }

        /** Every parameter name of the body, once per occurrence, in body order. */
        List<String> names() {
            return Arrays.stream(body.split("&"))
                    .map(pair -> URLDecoder.decode(pair.split("=", 2)[0], StandardCharsets.UTF_8))
                    .toList();
        }

        long occurrences(String name) {
            return names().stream().filter(name::equals).count();
        }
    }

    /**
     * The runtime's authorization-code flow, counting the pushes it serves — the proof that one flow
     * serves every scope set.
     */
    private static final class CountingFlow extends AuthorizationCodeFlow {

        private final AtomicInteger pushes = new AtomicInteger();

        CountingFlow(ClientConfiguration configuration, TokenEndpointClient tokenEndpointClient,
                TokenValidationBridge accessTokenBridge, IdTokenValidationBridge idTokenBridge,
                SenderConstraint senderConstraint) {
            super(configuration, tokenEndpointClient, accessTokenBridge, idTokenBridge, new IssValidator(),
                    senderConstraint);
        }

        @Override
        public AuthorizationRedirect authorizePushed(ProviderMetadata metadata,
                ClientAuthentication clientAuthentication, AuthorizationRequestOptions options) {
            pushes.incrementAndGet();
            return super.authorizePushed(metadata, clientAuthentication, options);
        }
    }

    private final TokenValidator tokenValidator = TokenValidator.builder()
            .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();
    private final ClientAuthentication clientAuthentication = new ClientSecretBasicAuth(CLIENT_ID, CLIENT_SECRET);
    /** The proof key every flow of one fixture shares; its thumbprint is what each proof must name. */
    private final DpopProofGenerator proofGenerator = new DpopProofGenerator(TestSigningKeys.ecKeyPair(), "ES256");
    /** Every push the pushed-authorization-request endpoint received, in arrival order. */
    private final List<Received> pushes = new CopyOnWriteArrayList<>();
    /** Every grant the token endpoint received, in arrival order. */
    private final List<Received> grants = new CopyOnWriteArrayList<>();
    /** The answers the pushed-authorization-request endpoint serves next; empty means accepting. */
    private final Queue<MockResponse> scripted = new ConcurrentLinkedQueue<>();
    /** The flow of the seam the last {@link #flows(ClientConfiguration)} call built. */
    private CountingFlow flow;

    /**
     * The identity provider: the pushed-authorization-request endpoint records each push and answers
     * from the script, and once the script is empty with {@code 201} and a {@code request_uri}; the
     * token endpoint records each grant and refuses it with {@code invalid_grant}.
     *
     * @return the identity-provider dispatcher
     */
    public ModuleDispatcherElement getModuleDispatcher() {
        return new ModuleDispatcherElement() {

            @Override
            public String getBaseUrl() {
                return "/";
            }

            @Override
            public Optional<MockResponse> handlePost(RecordedRequest request) {
                ByteString body = request.getBody();
                Received received = new Received(request.getHeaders(), body == null ? "" : body.utf8());
                String path = request.getUrl().encodedPath();
                if (PAR_PATH.equals(path)) {
                    pushes.add(received);
                    MockResponse next = scripted.poll();
                    return Optional.of(next == null ? accepted(REQUEST_URI_PREFIX + "default") : next);
                }
                if (TOKEN_PATH.equals(path)) {
                    grants.add(received);
                    return Optional.of(json(400, "{\"error\":\"invalid_grant\"}"));
                }
                return Optional.empty();
            }

            @Override
            public Set<HttpMethodMapper> supportedMethods() {
                return Set.of(HttpMethodMapper.POST);
            }
        };
    }

    // ---------------------------------------------------------------------------------------------
    // The browser location
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @EnumSource(Leg.class)
    @DisplayName("Location: should carry exactly client_id and request_uri, and every authorization parameter only in the push")
    void shouldCarryOnlyClientIdAndRequestUriInTheLocation(Leg leg, URIBuilder uriBuilder) {
        String requestUri = REQUEST_URI_PREFIX + Generators.letterStrings(16, 24).next();
        scripted.add(accepted(requestUri));

        Started started = leg.start(flows(), metadata(uriBuilder), SCOPED);

        Map<String, String> location = locationQuery(started.location());
        Map<String, String> pushed = singlePush().form();
        assertAll("the location of a pushed " + leg,
                () -> assertEquals(List.of(PARAM_CLIENT_ID, PARAM_REQUEST_URI), List.copyOf(location.keySet()),
                        "exactly two parameters, client_id first"),
                () -> assertEquals(CLIENT_ID, location.get(PARAM_CLIENT_ID),
                        "the client id survives its form encoding"),
                () -> assertEquals(requestUri, location.get(PARAM_REQUEST_URI),
                        "the request_uri is the one the identity provider answered"),
                () -> assertTrue(AUTHORIZATION_PARAMETERS.stream().noneMatch(location::containsKey),
                        "no authorization parameter reaches the browser: " + location.keySet()),
                // Matched control: the same names are present where they belong, so the absence above is
                // the absence of a parameter the request does carry.
                () -> assertTrue(pushed.keySet().containsAll(AUTHORIZATION_PARAMETERS),
                        "every authorization parameter is in the push: " + pushed.keySet()));
    }

    /** Keeps the encoding assertion above honest: the client id is not URL-safe as it stands. */
    @Test
    @DisplayName("Location: the client id is not URL-safe as it stands (control of the encoding assertion)")
    void shouldUseAClientIdTheFormEncodingHasToEscape() {
        assertNotEquals(CLIENT_ID, URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Location: the step-up re-drive pushes acr_values and max_age and keeps them out of the location")
    void shouldPushTheElevatedParametersOfTheStepUpOnly(URIBuilder uriBuilder) {
        ScopedEngineFlows flows = flows();
        ProviderMetadata metadata = metadata(uriBuilder);

        Started stepUp = Leg.STEP_UP.start(flows, metadata, SCOPED);
        Started login = Leg.LOGIN.start(flows, metadata, SCOPED);

        Map<String, String> stepUpPush = pushes.getFirst().form();
        Map<String, String> loginPush = pushes.getLast().form();
        assertAll("the elevated parameters of the step-up re-drive",
                () -> assertEquals(STEP_UP_ACR, stepUpPush.get("acr_values")),
                () -> assertEquals(Integer.toString(STEP_UP_MAX_AGE), stepUpPush.get("max_age")),
                () -> assertEquals(Optional.of(STEP_UP_ACR), stepUp.context().acrValues(),
                        "the persisted context carries the challenge, so the callback can check it"),
                () -> assertFalse(locationQuery(stepUp.location()).containsKey("acr_values"),
                        "the elevated parameters never reach the location"),
                () -> assertFalse(loginPush.containsKey("acr_values"), "a login requests no authentication context"),
                () -> assertFalse(loginPush.containsKey("max_age"), "nor an authentication age"),
                () -> assertEquals(Optional.empty(), login.context().acrValues()));
    }

    @Test
    @DisplayName("Location: should keep the query an authorization endpoint carries, and push none of it")
    void shouldKeepTheQueryOfTheAuthorizationEndpoint(URIBuilder uriBuilder) {
        ProviderMetadata metadata = metadata(uriBuilder);
        metadata.authorizationEndpoint = AUTHORIZATION_ENDPOINT + "?tenant=blue";

        String location = flows().authorize(metadata, SCOPED).authorizationUrl();

        assertAll("an authorization endpoint with a query of its own",
                () -> assertEquals(List.of("tenant", PARAM_CLIENT_ID, PARAM_REQUEST_URI),
                        List.copyOf(locationQuery(location).keySet()),
                        "the endpoint's own parameter stays, followed by the two of the pushed request"),
                () -> assertFalse(singlePush().form().containsKey("tenant"),
                        "the endpoint's own parameter is no authorization parameter and is not pushed"));
    }

    // ---------------------------------------------------------------------------------------------
    // What the push carries
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @EnumSource(Leg.class)
    @DisplayName("Push: should request response_mode=query on every leg")
    void shouldRequestQueryResponseMode(Leg leg, URIBuilder uriBuilder) {
        leg.start(flows(), metadata(uriBuilder), SCOPED);

        Received pushed = singlePush();
        assertAll("the response mode of a pushed " + leg,
                () -> assertEquals(1, pushed.occurrences(PARAM_RESPONSE_MODE), "exactly one response_mode"),
                () -> assertEquals(ResponseMode.QUERY.wireValue(), pushed.form().get(PARAM_RESPONSE_MODE),
                        "the callback is a top-level GET, the navigation the Lax binding cookie is sent on"),
                // Matched control: query is the seam's choice, not the engine's default.
                () -> assertEquals(ResponseMode.FORM_POST, AuthorizationRequestOptions.DEFAULTS.getResponseMode(),
                        "without the seam the engine requests form_post"));
    }

    @Test
    @DisplayName("Push: should carry exactly one prompt=none on the silent widening and none on every other leg")
    void shouldCarryPromptNoneOnTheSilentWideningOnly(URIBuilder uriBuilder) {
        ScopedEngineFlows flows = flows();
        ProviderMetadata metadata = metadata(uriBuilder);

        Leg.SILENT_WIDENING.start(flows, metadata, SCOPED);
        Leg.INTERACTIVE_WIDENING.start(flows, metadata, SCOPED);
        Leg.LOGIN.start(flows, metadata, SCOPED);
        Leg.STEP_UP.start(flows, metadata, SCOPED);

        assertEquals(4, pushes.size(), "every leg pushed its request");
        assertAll("the prompt of each leg",
                () -> assertEquals(1, pushes.get(0).occurrences(PARAM_PROMPT), "exactly one prompt on the silent attempt"),
                () -> assertEquals("none", pushes.get(0).form().get(PARAM_PROMPT)),
                () -> assertEquals(0, pushes.get(1).occurrences(PARAM_PROMPT),
                        "the interactive attempt lets the identity provider interact"),
                () -> assertEquals(0, pushes.get(2).occurrences(PARAM_PROMPT),
                        "a widening never leaks prompt=none into a later login"),
                () -> assertEquals(0, pushes.get(3).occurrences(PARAM_PROMPT), "nor into a step-up re-drive"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Leg.class)
    @DisplayName("Push: the scope is the canonical form of the requested set, duplicates collapsed")
    void shouldPushTheCanonicalScopeSet(Leg leg, URIBuilder uriBuilder) {
        leg.start(flows(), metadata(uriBuilder), SCOPED_UNORDERED);

        Received pushed = singlePush();
        assertAll("the scope of a pushed " + leg,
                () -> assertEquals(1, pushed.occurrences(PARAM_SCOPE), "exactly one scope parameter"),
                () -> assertEquals(SCOPED_CANONICAL, pushed.form().get(PARAM_SCOPE),
                        "deduplicated and sorted, whatever order and repetition the set was named in"));
    }

    @Test
    @DisplayName("Push: two orderings of one set send one scope value, a different set sends its own")
    void shouldSendOneScopeValuePerCanonicalSet(URIBuilder uriBuilder) {
        ScopedEngineFlows flows = flows();
        ProviderMetadata metadata = metadata(uriBuilder);

        flows.authorize(metadata, SCOPED);
        flows.authorize(metadata, SCOPED_UNORDERED);
        flows.authorize(metadata, List.of("openid"));

        assertAll("one scope value per canonical set",
                () -> assertEquals(SCOPED_CANONICAL, pushes.get(0).form().get(PARAM_SCOPE)),
                () -> assertEquals(pushes.get(0).form().get(PARAM_SCOPE), pushes.get(1).form().get(PARAM_SCOPE),
                        "the same canonical set sends the same scope"),
                // Matched control: a different set is not answered with the first one.
                () -> assertEquals("openid", pushes.get(2).form().get(PARAM_SCOPE),
                        "a different canonical set sends its own scope"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Leg.class)
    @DisplayName("Push: code_challenge, state and nonce exactly once, bound to the persisted context")
    void shouldPushEachTransactionParameterOnceBoundToTheContext(Leg leg, URIBuilder uriBuilder) {
        Started started = leg.start(flows(), metadata(uriBuilder), SCOPED);

        Received pushed = singlePush();
        Map<String, String> form = pushed.form();
        FlowContext context = started.context();
        assertAll("the transaction parameters of a pushed " + leg,
                () -> assertEquals(1, pushed.occurrences(PARAM_CODE_CHALLENGE), "one code_challenge"),
                () -> assertEquals(1, pushed.occurrences(PARAM_STATE), "one state"),
                () -> assertEquals(1, pushed.occurrences(PARAM_NONCE), "one nonce"),
                () -> assertEquals(context.pkceChallenge().codeChallenge(), form.get(PARAM_CODE_CHALLENGE)),
                () -> assertEquals("S256", form.get("code_challenge_method")),
                () -> assertEquals(context.state(), form.get(PARAM_STATE),
                        "the pushed state is the one the callback is checked against"),
                () -> assertEquals(context.nonce(), form.get(PARAM_NONCE)),
                () -> assertEquals(pushed.names().size(), Set.copyOf(pushed.names()).size(),
                        "no parameter is named twice in the push: " + pushed.names()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Leg.class)
    @DisplayName("Push: should carry no dpop_jkt and no proof, and be authenticated with the client credential")
    void shouldPushNoDpopJkt(Leg leg, URIBuilder uriBuilder) {
        leg.start(flows(), metadata(uriBuilder), SCOPED);

        Received pushed = singlePush();
        assertAll("the binding and authentication of a pushed " + leg,
                () -> assertEquals(0, pushed.occurrences("dpop_jkt"), "the gateway pushes no dpop_jkt"),
                // Matched control: the reader does see the parameters the push carries.
                () -> assertEquals(1, pushed.occurrences(PARAM_CODE_CHALLENGE)),
                () -> assertNull(pushed.headers().get(DPOP_HEADER), "the push carries no DPoP proof"),
                () -> assertNotNull(pushed.headers().get(AUTHORIZATION),
                        "the push is authenticated with the runtime's client credential"),
                () -> assertFalse(pushed.form().containsKey("client_secret"),
                        "the secret travels in the header only, never as a form parameter"));
    }

    @Test
    @DisplayName("Edge: an empty scope set pushes no scope parameter, and a non-empty one does")
    void shouldPushNoScopeForTheEmptySet(URIBuilder uriBuilder) {
        ScopedEngineFlows flows = flows();
        ProviderMetadata metadata = metadata(uriBuilder);

        flows.authorize(metadata, List.of());
        flows.authorize(metadata, List.of("openid"));

        assertAll("the empty set is sent as no scope override",
                () -> assertEquals(2, pushes.size(), "the empty set is pushed, not refused"),
                () -> assertEquals(0, pushes.getFirst().occurrences(PARAM_SCOPE),
                        "the configuration carries no scope, so the request carries none"),
                () -> assertEquals("openid", pushes.getLast().form().get(PARAM_SCOPE),
                        "matched control: a non-empty set does carry its scope"));
    }

    @Test
    @DisplayName("Edge: one engine flow serves every scope set, and no set leaks into the next call")
    void shouldServeEveryScopeSetThroughTheOneFlow(URIBuilder uriBuilder) {
        ScopedEngineFlows flows = flows();
        ProviderMetadata metadata = metadata(uriBuilder);

        flows.authorize(metadata, SCOPED);
        flows.widen(metadata, List.of("openid"), true);
        flows.authorize(metadata, SCOPED_UNORDERED);

        assertAll("the per-call options on the one flow",
                () -> assertEquals(3, flow.pushes.get(), "every login and widening is served by the one flow"),
                () -> assertEquals(List.of(SCOPED_CANONICAL, "openid", SCOPED_CANONICAL),
                        pushes.stream().map(pushed -> pushed.form().get(PARAM_SCOPE)).toList(),
                        "each call sends its own set; the widening's set is not carried into the next login"),
                () -> assertEquals(1, pushes.get(1).occurrences(PARAM_PROMPT),
                        "the widening's prompt=none is its own"),
                () -> assertEquals(0, pushes.get(2).occurrences(PARAM_PROMPT),
                        "and is not carried into the next login either"));
    }

    // ---------------------------------------------------------------------------------------------
    // Refusals
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @EnumSource(Leg.class)
    @DisplayName("Refusal: a provider without a pushed-authorization-request endpoint is refused, nothing sent")
    void shouldRefuseAProviderWithoutAParEndpoint(Leg leg) {
        ScopedEngineFlows flows = flows();
        ProviderMetadata metadata = metadata((String) null);

        GatewayException refused = assertThrows(GatewayException.class,
                () -> leg.start(flows, metadata, SCOPED));

        assertRefusedWithoutARequest(refused, REASON_NO_PAR_ENDPOINT);
    }

    @ParameterizedTest(name = "pushed_authorization_request_endpoint ''{0}''")
    @ValueSource(strings = {"", "  "})
    @DisplayName("Refusal: a blank pushed-authorization-request endpoint is refused as an absent one")
    void shouldRefuseABlankParEndpoint(String blank) {
        ScopedEngineFlows flows = flows();
        ProviderMetadata metadata = metadata(blank);

        GatewayException refused = assertThrows(GatewayException.class, () -> flows.authorize(metadata, SCOPED));

        assertRefusedWithoutARequest(refused, REASON_NO_PAR_ENDPOINT);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(PushFailure.class)
    @DisplayName("Refusal: a refused or unparsable push answer refuses the login with the 502 event")
    void shouldRefuseAFailedPush(PushFailure failure, URIBuilder uriBuilder) {
        ScopedEngineFlows flows = flows();
        ProviderMetadata metadata = metadata(uriBuilder);
        scripted.add(failure.answer());

        GatewayException refused = assertThrows(GatewayException.class, () -> flows.authorize(metadata, SCOPED),
                failure.name());

        assertAll("a push that failed: " + failure,
                () -> assertRefusal(refused, REASON_PUSH_FAILED),
                () -> assertInstanceOf(TransportException.class, refused.getCause(),
                        "the engine failure is kept as the cause"),
                () -> assertEquals(1, pushes.size(), "the request did reach the endpoint — the refusal is of its answer"));
    }

    @Test
    @DisplayName("Refusal: the accepting answer of the same endpoint yields the redirect (matched control)")
    void shouldYieldTheRedirectWhenThePushIsAccepted(URIBuilder uriBuilder) {
        AuthorizationCodeFlow.AuthorizationRedirect redirect = flows().authorize(metadata(uriBuilder), SCOPED);

        assertEquals(1, pushes.size(), "the push reached the endpoint");
        assertEquals(REQUEST_URI_PREFIX + "default", locationQuery(redirect.authorizationUrl()).get(PARAM_REQUEST_URI));
        assertTrue(refusalRecords().isEmpty(), "an accepted push records no refusal");
    }

    @Test
    @DisplayName("Refusal: a step-up push the endpoint refuses is refused like a login push")
    void shouldRefuseAFailedStepUpPush(URIBuilder uriBuilder) {
        ScopedEngineFlows flows = flows();
        ProviderMetadata metadata = metadata(uriBuilder);
        scripted.add(PushFailure.SERVER_ERROR.answer());

        GatewayException refused = assertThrows(GatewayException.class,
                () -> Leg.STEP_UP.start(flows, metadata, SCOPED));

        assertRefusal(refused, REASON_PUSH_FAILED);
        assertInstanceOf(TransportException.class, refused.getCause());
    }

    @Test
    @DisplayName("Refusal: an endpoint the engine refuses to dial is refused as a failed push, nothing sent")
    void shouldRefuseAPushThatCannotBeDialled() {
        // A plain-HTTP endpoint under a configuration that does not allow it is refused by the engine
        // before anything is sent — a transport failure that needs no listener and no free port.
        ClientConfiguration strict = ClientConfiguration.builder().issuer(ISSUER).clientId(CLIENT_ID)
                .clientSecret(CLIENT_SECRET).authMethod(ClientAuthMethod.CLIENT_SECRET_BASIC).scopes(List.of())
                .redirectUri(REDIRECT_URI).build();
        ScopedEngineFlows flows = flows(strict);
        ProviderMetadata metadata = metadata("http://idp.example.com/par");

        GatewayException refused = assertThrows(GatewayException.class, () -> flows.authorize(metadata, SCOPED));

        assertAll("a push the engine refuses to dial",
                () -> assertRefusal(refused, REASON_PUSH_FAILED),
                () -> assertEquals(List.of(), pushes, "nothing was sent"));
    }

    @ParameterizedTest(name = "scope ''{0}''")
    @ValueSource(strings = {"orders read", "", " ", "orders\tread"})
    @DisplayName("Refusal: a scope that is no single scope-token is refused before anything is sent")
    void shouldRefuseAnUnparsableScope(String scope, URIBuilder uriBuilder) {
        ScopedEngineFlows flows = flows();
        ProviderMetadata metadata = metadata(uriBuilder);
        List<String> scopes = List.of("openid", scope);

        GatewayException login = assertThrows(GatewayException.class, () -> flows.authorize(metadata, scopes));
        GatewayException stepUp = assertThrows(GatewayException.class,
                () -> Leg.STEP_UP.start(flows, metadata, scopes));

        assertAll("an unparsable scope parameter",
                () -> assertEquals("Pushed authorization request refused (" + REASON_INVALID_REQUEST + ")",
                        login.getMessage()),
                () -> assertEquals(login.getMessage(), stepUp.getMessage(), "the step-up re-drive refuses it alike"),
                () -> assertEquals(EventType.UPSTREAM_ERROR, login.getEventType()),
                () -> assertNull(login.getCause(), "the engine's message can name the scope; it is not chained"),
                () -> assertEquals(List.of(), pushes, "nothing was sent"));
    }

    @Test
    @DisplayName("Refusal: metadata without an authorization endpoint or without PKCE S256 is refused, nothing sent")
    void shouldRefuseMetadataTheEngineCannotFormARequestFrom(URIBuilder uriBuilder) {
        ScopedEngineFlows flows = flows();
        ProviderMetadata withoutEndpoint = metadata(uriBuilder);
        withoutEndpoint.authorizationEndpoint = null;
        ProviderMetadata withoutS256 = metadata(uriBuilder);
        withoutS256.codeChallengeMethodsSupported = List.of("plain");

        GatewayException noEndpoint = assertThrows(GatewayException.class,
                () -> flows.authorize(withoutEndpoint, SCOPED));
        GatewayException noS256 = assertThrows(GatewayException.class, () -> flows.widen(withoutS256, SCOPED, true));

        assertAll("a request the engine cannot form",
                () -> assertRefusal(noEndpoint, REASON_INVALID_REQUEST),
                () -> assertEquals(noEndpoint.getMessage(), noS256.getMessage()),
                () -> assertNull(noS256.getCause()),
                () -> assertEquals(List.of(), pushes, "nothing was sent"));
    }

    // ---------------------------------------------------------------------------------------------
    // The refusal record (ADR-0051)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Record: a refusal is a WARN once per reason and a DEBUG on every repetition")
    void shouldLatchTheRefusalRecordPerReason(URIBuilder uriBuilder) {
        ScopedEngineFlows flows = flows();
        ProviderMetadata withoutEndpoint = metadata((String) null);
        ProviderMetadata withEndpoint = metadata(uriBuilder);
        List<String> unparsable = List.of("orders read");

        assertThrows(GatewayException.class, () -> flows.authorize(withoutEndpoint, SCOPED));
        int warnsAfterTheFirst = refusalRecords().size();
        assertThrows(GatewayException.class, () -> flows.widen(withoutEndpoint, SCOPED, true));
        assertThrows(GatewayException.class, () -> Leg.STEP_UP.start(flows, withoutEndpoint, SCOPED));
        int warnsAfterTheRepetitions = refusalRecords().size();
        assertThrows(GatewayException.class, () -> flows.authorize(withEndpoint, unparsable));

        List<String> warns = refusalRecords().stream().map(captured -> String.valueOf(captured.getMessage())).toList();
        assertAll("the refusal record is latched per reason, across the legs of one seam",
                () -> assertEquals(1, warnsAfterTheFirst, "the first refusal of a reason is a WARN"),
                () -> assertEquals(1, warnsAfterTheRepetitions, "its repetitions add no WARN, whichever leg"),
                () -> assertEquals(2, repetitionRecords(REASON_NO_PAR_ENDPOINT),
                        "each repetition is one DEBUG record naming the reason"),
                () -> assertEquals(2, warns.size(), "another reason is recorded with its own WARN: " + warns),
                () -> assertTrue(warns.getFirst().contains(REASON_NO_PAR_ENDPOINT), warns.getFirst()),
                () -> assertTrue(warns.getLast().contains(REASON_INVALID_REQUEST), warns.getLast()),
                () -> assertEquals(0, repetitionRecords(REASON_INVALID_REQUEST),
                        "the first refusal of the second reason is no repetition"));
    }

    @Test
    @DisplayName("Record: the latch is held per instance, so a second seam reports the same reason again")
    void shouldHoldTheLatchPerInstance() {
        ScopedEngineFlows first = flows();
        ScopedEngineFlows second = flows();
        ProviderMetadata withoutEndpoint = metadata((String) null);

        assertThrows(GatewayException.class, () -> first.authorize(withoutEndpoint, SCOPED));
        assertThrows(GatewayException.class, () -> second.authorize(withoutEndpoint, SCOPED));

        assertEquals(2, refusalRecords().size(), "each seam records the reason once");
    }

    @Test
    @DisplayName("Record: the text names the shared consequence and no caller")
    void shouldRecordTheSharedConsequence() {
        ProviderMetadata withoutEndpoint = metadata((String) null);

        assertThrows(GatewayException.class, () -> Leg.STEP_UP.start(flows(), withoutEndpoint, SCOPED));

        String recorded = String.valueOf(refusalRecords().getFirst().getMessage());
        assertTrue(recorded.endsWith("Pushed authorization request refused: " + REASON_NO_PAR_ENDPOINT
                        + " — no redirect to the identity provider was issued; further refusals with this reason"
                        + " stay at DEBUG"),
                "the record states what holds for a login, a widening and a step-up re-drive alike: " + recorded);
    }

    @Test
    @DisplayName("Record: discloses neither state, nonce, PKCE challenge and client secret nor the request_uri")
    void shouldDiscloseNoTransactionValue(URIBuilder uriBuilder) {
        ScopedEngineFlows flows = flows();
        ProviderMetadata metadata = metadata(uriBuilder);
        String requestUri = REQUEST_URI_PREFIX + Generators.letterStrings(16, 24).next();
        scripted.add(PushFailure.SERVER_ERROR.answer());
        scripted.add(accepted(requestUri));

        GatewayException failed = assertThrows(GatewayException.class, () -> flows.authorize(metadata, SCOPED));
        GatewayException invalid = assertThrows(GatewayException.class,
                () -> flows.authorize(metadata, List.of("orders read")));
        Started accepted = Leg.SILENT_WIDENING.start(flows, metadata, SCOPED);

        assertEquals(requestUri, locationQuery(accepted.location()).get(PARAM_REQUEST_URI), "the second push was accepted");
        List<String> secrets = new ArrayList<>(List.of(requestUri, CLIENT_SECRET, accepted.location()));
        for (Received pushed : pushes) {
            Map<String, String> form = pushed.form();
            secrets.addAll(List.of(form.get(PARAM_STATE), form.get(PARAM_NONCE), form.get(PARAM_CODE_CHALLENGE)));
        }
        assertNoneIsDisclosed(secrets, failed, invalid);
    }

    // ---------------------------------------------------------------------------------------------
    // The refresh leg
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Refresh: should post the canonical form of the passed set as the grant's scope")
    void shouldRefreshWithTheCanonicalScopeSet(URIBuilder uriBuilder) {
        ScopedEngineFlows flows = flows();
        ProviderMetadata metadata = metadata(uriBuilder);
        String refreshToken = Generators.letterStrings(16, 32).next();

        assertThrows(TransportException.class, () -> flows.refresh(metadata, refreshToken, SCOPED_UNORDERED),
                "the stub endpoint refuses the grant; only the request it received matters here");

        assertEquals(1, grants.size(), "exactly one refresh grant reached the token endpoint");
        Map<String, String> form = grants.getFirst().form();
        assertAll("the refresh grant",
                () -> assertEquals("refresh_token", form.get("grant_type")),
                () -> assertEquals(refreshToken, form.get("refresh_token")),
                () -> assertEquals(SCOPED_CANONICAL, form.get(PARAM_SCOPE),
                        "the grant carries exactly the passed set, canonicalised"));
    }

    @Test
    @DisplayName("Refresh: an empty set posts no scope, a non-empty one does, and each refresh posts its own grant")
    void shouldRefreshWithoutScopeForTheEmptySet(URIBuilder uriBuilder) {
        ScopedEngineFlows flows = flows();
        ProviderMetadata metadata = metadata(uriBuilder);

        assertThrows(TransportException.class, () -> flows.refresh(metadata, "first-refresh-token", List.of()));
        assertThrows(TransportException.class, () -> flows.refresh(metadata, "second-refresh-token", List.of("openid")));

        assertAll("one grant per refresh",
                () -> assertEquals(2, grants.size()),
                () -> assertFalse(grants.getFirst().form().containsKey(PARAM_SCOPE),
                        "the empty set sends no scope parameter"),
                () -> assertEquals("openid", grants.getLast().form().get(PARAM_SCOPE),
                        "matched control: a non-empty set sends its scope"));
    }

    @Test
    @DisplayName("Refresh: two scope sets present proofs signed with the one shared key")
    void shouldSignEveryRefreshGrantWithTheSharedKey(URIBuilder uriBuilder) throws Exception {
        ScopedEngineFlows flows = flows();
        ProviderMetadata metadata = metadata(uriBuilder);

        assertThrows(TransportException.class, () -> flows.refresh(metadata, "base-refresh-token", List.of("openid")));
        assertThrows(TransportException.class, () -> flows.refresh(metadata, "scoped-refresh-token", SCOPED));

        assertEquals(2, grants.size(), "each refresh grant reached the token endpoint");
        String firstProof = grants.getFirst().headers().get(DPOP_HEADER);
        String lastProof = grants.getLast().headers().get(DPOP_HEADER);
        assertNotNull(firstProof, "a refresh grant was posted without a DPoP header");
        assertNotNull(lastProof, "a refresh grant was posted without a DPoP header");
        assertAll("the sender constraint is shared across scope sets, not built per set",
                () -> assertEquals("dpop+jwt", proofHeader(firstProof).path("typ").asText()),
                () -> assertEquals(proofGenerator.jkt(), proofKeyThumbprint(firstProof),
                        "the proof names the key the seam was given"),
                () -> assertEquals(proofKeyThumbprint(firstProof), proofKeyThumbprint(lastProof),
                        "a second scope set proves possession of the same key"),
                () -> assertNotEquals(firstProof, lastProof, "each grant carries its own single-use proof"));
    }

    // ---------------------------------------------------------------------------------------------
    // The canonical scope set and the null contract
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Canonical: should deduplicate and sort a scope set into its canonical list")
    void shouldCanonicalize() {
        assertEquals(List.of("email", "openid", "profile"),
                ScopedEngineFlows.canonical(List.of("profile", "openid", "email", "openid")));
        assertEquals(List.of(), ScopedEngineFlows.canonical(List.of()), "the empty set stays empty");
    }

    @Test
    @DisplayName("Canonical: should reject a null scope set and a null scope name")
    void shouldRejectNullsInTheScopeSet() {
        List<String> withNullName = Arrays.asList("openid", null);

        assertThrows(NullPointerException.class, () -> ScopedEngineFlows.canonical(null));
        assertThrows(NullPointerException.class, () -> ScopedEngineFlows.canonical(withNullName));
    }

    @Test
    @DisplayName("Contract: should reject absent collaborators and absent arguments")
    void shouldRejectAbsentArguments() {
        ClientConfiguration configuration = configuration();
        TokenEndpointClient tokenClient = new TokenEndpointClient(configuration);
        AuthorizationCodeFlow codeFlow = new AuthorizationCodeFlow(configuration, tokenClient,
                new TokenValidationBridge(tokenValidator), new IdTokenValidationBridge(tokenValidator));
        RefreshFlow refreshFlow = new RefreshFlow(configuration, tokenClient, new TokenValidationBridge(tokenValidator),
                clientAuthentication);
        ScopedEngineFlows flows = flows();
        ProviderMetadata metadata = metadata((String) null);
        StepUpChallenge challenge = new StepUpChallenge(STEP_UP_ACR, null);

        assertAll("the null contract",
                () -> assertThrows(NullPointerException.class,
                        () -> new ScopedEngineFlows(null, codeFlow, refreshFlow, clientAuthentication)),
                () -> assertThrows(NullPointerException.class,
                        () -> new ScopedEngineFlows(configuration, null, refreshFlow, clientAuthentication)),
                () -> assertThrows(NullPointerException.class,
                        () -> new ScopedEngineFlows(configuration, codeFlow, null, clientAuthentication)),
                () -> assertThrows(NullPointerException.class,
                        () -> new ScopedEngineFlows(configuration, codeFlow, refreshFlow, null)),
                () -> assertThrows(NullPointerException.class, () -> flows.authorize(null, SCOPED)),
                () -> assertThrows(NullPointerException.class, () -> flows.widen(null, SCOPED, true)),
                () -> assertThrows(NullPointerException.class, () -> flows.stepUp(null, challenge, SCOPED)),
                () -> assertThrows(NullPointerException.class, () -> flows.stepUp(metadata, null, SCOPED)),
                () -> assertThrows(NullPointerException.class, () -> flows.refresh(null, "rt", SCOPED)),
                () -> assertThrows(NullPointerException.class, () -> flows.refresh(metadata, null, SCOPED)));
    }

    // ---------------------------------------------------------------------------------------------
    // Assertions
    // ---------------------------------------------------------------------------------------------

    private Received singlePush() {
        assertEquals(1, pushes.size(), "exactly one request is pushed");
        return pushes.getFirst();
    }

    /** Asserts a refusal that was decided before anything was sent. */
    private void assertRefusedWithoutARequest(GatewayException refused, String reason) {
        assertAll("a refusal decided before the push",
                () -> assertRefusal(refused, reason),
                () -> assertNull(refused.getCause(), "nothing was sent, so there is no engine failure to chain"),
                () -> assertEquals(List.of(), pushes, "the endpoint received no request"));
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
     * Asserts that no captured record, down to {@code DEBUG}, and no message of a refusal or of a cause
     * it chains carries one of {@code secrets}. The control comes first: a {@code DEBUG} record of the
     * seam and of the engine client it pushes through is captured.
     */
    private static void assertNoneIsDisclosed(List<String> secrets, Throwable... refusals) {
        SheriffDebugCapture.assertDebugIsCaptured(ScopedEngineFlows.class, ParClient.class);
        List<LogRecord> records = SheriffDebugCapture.capturedRecords();
        assertFalse(records.isEmpty(), "no record was captured at all, so the absence would prove nothing");
        List<Executable> checks = new ArrayList<>();
        for (LogRecord captured : records) {
            String rendered = SheriffDebugCapture.rendered(captured);
            checks.add(() -> assertTrue(secrets.stream().noneMatch(rendered::contains),
                    "a " + captured.getLevel() + " record of " + captured.getLoggerName()
                            + " carries one of the values that must not be disclosed"));
        }
        for (Throwable refusal : refusals) {
            for (Throwable cause = refusal; cause != null; cause = cause.getCause()) {
                String message = String.valueOf(cause.getMessage());
                checks.add(() -> assertTrue(secrets.stream().noneMatch(message::contains),
                        "a refusal carries one of the values that must not be disclosed: " + message));
            }
        }
        assertAll("none of the given values is disclosed", checks);
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

    // ---------------------------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------------------------

    private ScopedEngineFlows flows() {
        return flows(configuration());
    }

    /** The seam as the runtime assembles it: one flow and one refresh flow over one configuration. */
    private ScopedEngineFlows flows(ClientConfiguration configuration) {
        TokenEndpointClient tokenClient = new TokenEndpointClient(configuration);
        TokenValidationBridge tokenBridge = new TokenValidationBridge(tokenValidator);
        SenderConstraint senderConstraint = SenderConstraint.dpop(proofGenerator);
        flow = new CountingFlow(configuration, tokenClient, tokenBridge, new IdTokenValidationBridge(tokenValidator),
                senderConstraint);
        return new ScopedEngineFlows(configuration, flow,
                new RefreshFlow(configuration, tokenClient, tokenBridge, clientAuthentication, senderConstraint),
                clientAuthentication);
    }

    /**
     * The runtime's configuration shape: no scope of its own; plain HTTP is allowed because the
     * identity provider is in-process.
     */
    private static ClientConfiguration configuration() {
        return ClientConfiguration.builder()
                .issuer(ISSUER).clientId(CLIENT_ID).clientSecret(CLIENT_SECRET)
                .authMethod(ClientAuthMethod.CLIENT_SECRET_BASIC)
                .scopes(List.of()).redirectUri(REDIRECT_URI)
                .allowInsecureHttp(true)
                .allowLoopbackEgress(true)
                .build();
    }

    private static ProviderMetadata metadata(URIBuilder uriBuilder) {
        ProviderMetadata metadata = metadata(uriBuilder.addPathSegment("par").buildAsString());
        metadata.tokenEndpoint = metadata.pushedAuthorizationRequestEndpoint.replace(PAR_PATH, TOKEN_PATH);
        return metadata;
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

    /** The decoded query of a location on the authorization endpoint, in URL order. */
    private static Map<String, String> locationQuery(String location) {
        assertTrue(location.startsWith(AUTHORIZATION_ENDPOINT + "?"),
                "the location is built on the authorization endpoint: " + location);
        return formPairs(location.substring(AUTHORIZATION_ENDPOINT.length() + 1));
    }

    /**
     * Decodes a form-encoded string — a query or a request body — into its pairs, in order, refusing a
     * name that occurs twice.
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

    /** The decoded JOSE header of a compact DPoP proof. */
    private static JsonNode proofHeader(String proof) throws IOException {
        return JSON.readTree(Base64.getUrlDecoder().decode(proof.split("\\.")[0]));
    }

    /** The RFC 7638 thumbprint of the public key a DPoP proof embeds in its header. */
    private static String proofKeyThumbprint(String proof) throws IOException {
        Map<String, Object> jwk = JSON.convertValue(proofHeader(proof).path("jwk"),
                new TypeReference<Map<String, Object>>() {
                });
        return JwkThumbprintUtil.computeThumbprint(jwk);
    }
}
