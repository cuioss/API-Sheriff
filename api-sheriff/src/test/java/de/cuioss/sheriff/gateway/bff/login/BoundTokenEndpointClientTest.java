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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.LogRecord;
import java.util.stream.Stream;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.cuioss.sheriff.gateway.auth.TestTlsConfigurationRegistry;
import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.sheriff.gateway.bff.client.TestSigningKeys;
import de.cuioss.sheriff.gateway.testsupport.StubIdentityProvider;
import de.cuioss.sheriff.token.client.config.ClientAuthMethod;
import de.cuioss.sheriff.token.client.config.ClientConfiguration;
import de.cuioss.sheriff.token.client.dpop.DpopProofGenerator;
import de.cuioss.sheriff.token.client.dpop.SenderConstraint;
import de.cuioss.sheriff.token.client.flow.CredentialRejectedException;
import de.cuioss.sheriff.token.client.flow.RedeemedResponseException;
import de.cuioss.sheriff.token.client.flow.RefreshFailureClassification;
import de.cuioss.sheriff.token.client.flow.RefreshFlow;
import de.cuioss.sheriff.token.client.token.TokenResponse;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link BoundTokenEndpointClient}: a token response is returned only when its type is
 * {@code DPoP} and its access token names the expected proof key in {@code cnf.jkt}; every other
 * success answer is refused as a redeemed response, recorded once, and leaks no token material.
 * <p>
 * The client is driven against the token endpoint of {@link StubIdentityProvider}. Each test scripts
 * the token response it needs. The client's own {@link ClientConfiguration} trusts the stub's root
 * certificate through its SSL context, and plain HTTP is not enabled. The access tokens are compact
 * JWS strings composed here: the class under test verifies no signature, so none is needed.
 * <p>
 * The refusals are matched controls over one fixture — each differs from the accepted response in a
 * single property.
 */
@EnableGeneratorController
@EnableTestLogger(rootLevel = TestLogLevel.DEBUG)
@DisplayName("BoundTokenEndpointClient — a token response must be bound to the proof key")
class BoundTokenEndpointClientTest {

    private static final String PROFILE = "stub-idp";
    private static final String GRANT_TYPE = "grant_type";
    private static final String AUTHORIZATION_CODE = "authorization_code";
    private static final String REFRESH_TOKEN = "refresh_token";
    private static final String TYPE_DPOP = "DPoP";
    private static final String TYPE_BEARER = "Bearer";
    private static final String LEG_CODE_EXCHANGE = "code-exchange";
    private static final String LEG_REFRESH = "refresh";
    private static final String LEG_OTHER = "other";
    private static final String REASON_TOKEN_TYPE = "token-type";
    private static final String REASON_UNREADABLE = "unreadable-access-token";
    private static final String REASON_CNF_ABSENT = "cnf-absent";
    private static final String REASON_CNF_MISMATCH = "cnf-mismatch";
    private static final String DPOP_HEADER = "DPoP";
    private static final String DPOP_NONCE_HEADER = "DPoP-Nonce";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    /** How the access token of a scripted response is composed. */
    enum AccessTokenShape {

        /** A compact JWS whose {@code cnf.jkt} is the thumbprint of the client's proof key. */
        BOUND,

        /** A compact JWS whose {@code cnf.jkt} is the thumbprint of another key. */
        BOUND_TO_ANOTHER_KEY,

        /** A compact JWS that carries no {@code cnf} claim. */
        WITHOUT_CNF,

        /** A compact JWS whose {@code cnf} object holds no {@code jkt}. */
        CNF_WITHOUT_JKT,

        /** A string that is not a compact JWS at all. */
        OPAQUE,

        /** Three segments whose payload segment decodes to something that is not JSON. */
        PAYLOAD_NOT_JSON
    }

    /**
     * One scripted token response: the values an identity provider would have issued, kept so a test
     * can assert that none of them reaches a log record or an exception message.
     *
     * @param accessToken  the scripted access token
     * @param refreshToken the scripted refresh token
     * @param idToken      the scripted ID token
     * @param thumbprint   the {@code jkt} the access token carries, empty when it carries none
     */
    private record Scripted(String accessToken, String refreshToken, String idToken, Optional<String> thumbprint) {

        List<String> secrets() {
            List<String> secrets = new ArrayList<>(List.of(accessToken, refreshToken, idToken));
            thumbprint.ifPresent(secrets::add);
            return secrets;
        }
    }

    private final DpopProofGenerator proofGenerator = new DpopProofGenerator(TestSigningKeys.ecKeyPair(), "ES256");
    private final SenderConstraint senderConstraint = SenderConstraint.dpop(proofGenerator);
    private final String otherKeyThumbprint = new DpopProofGenerator(TestSigningKeys.ecKeyPair(), "ES256").jkt();

    private StubIdentityProvider stub;
    private BoundTokenEndpointClient client;

    @BeforeEach
    void startStub() throws IOException {
        stub = StubIdentityProvider.start();
        client = new BoundTokenEndpointClient(configuration(), proofGenerator.jkt());
    }

    @AfterEach
    void stopStub() {
        stub.close();
    }

    static Stream<Arguments> unboundResponsesOnBothLegs() {
        List<Arguments> refusals = List.of(
                Arguments.of("type Bearer over the bound access token", TYPE_BEARER, AccessTokenShape.BOUND,
                        REASON_TOKEN_TYPE),
                Arguments.of("type DPoP with the jkt of another key", TYPE_DPOP,
                        AccessTokenShape.BOUND_TO_ANOTHER_KEY, REASON_CNF_MISMATCH),
                Arguments.of("type DPoP with no cnf claim", TYPE_DPOP, AccessTokenShape.WITHOUT_CNF,
                        REASON_CNF_ABSENT),
                Arguments.of("type DPoP with a cnf object that holds no jkt", TYPE_DPOP,
                        AccessTokenShape.CNF_WITHOUT_JKT, REASON_CNF_ABSENT),
                Arguments.of("type DPoP with an opaque access token", TYPE_DPOP, AccessTokenShape.OPAQUE,
                        REASON_UNREADABLE),
                Arguments.of("type DPoP with a three-segment access token whose payload is not JSON", TYPE_DPOP,
                        AccessTokenShape.PAYLOAD_NOT_JSON, REASON_UNREADABLE));
        return refusals.stream().flatMap(refusal -> Stream.of(
                Arguments.of(refusal.get()[0], refusal.get()[1], refusal.get()[2], refusal.get()[3],
                        AUTHORIZATION_CODE, LEG_CODE_EXCHANGE),
                Arguments.of(refusal.get()[0], refusal.get()[1], refusal.get()[2], refusal.get()[3],
                        REFRESH_TOKEN, LEG_REFRESH)));
    }

    static Stream<Arguments> requestsOutsideTheGrantTypeAllowList() {
        return Stream.of(
                Arguments.of("an unlisted grant_type", Map.of(GRANT_TYPE, "client_credentials")),
                Arguments.of("no grant_type at all", Map.of("scope", "openid")));
    }

    @ParameterizedTest(name = "token_type ''{0}''")
    @ValueSource(strings = {"DPoP", "dpop", "DPOP"})
    @DisplayName("Should return a response of type DPoP whose access token names the proof key, unchanged")
    void shouldReturnABoundResponseUnchanged(String tokenType) {
        Scripted scripted = script(tokenType, AccessTokenShape.BOUND);

        TokenResponse response = request(AUTHORIZATION_CODE);

        assertAll("the engine's response is handed back as it is",
                () -> assertEquals(scripted.accessToken(), response.accessToken),
                () -> assertEquals(tokenType, response.tokenType, "the type is compared, never rewritten"),
                () -> assertEquals(scripted.refreshToken(), response.refreshToken),
                () -> assertEquals(scripted.idToken(), response.idToken),
                () -> assertEquals(List.of(), refusalRecords(), "an accepted response is not recorded"));
    }

    @ParameterizedTest(name = "{0} — grant_type {4}")
    @MethodSource("unboundResponsesOnBothLegs")
    @DisplayName("Should refuse a success answer that is not bound to the proof key, as a redeemed response")
    void shouldRefuseAnUnboundResponse(String description, String tokenType, AccessTokenShape shape,
            String reason, String grantType, String leg) {
        Scripted scripted = script(tokenType, shape);

        RedeemedResponseException refused =
                assertThrows(RedeemedResponseException.class, () -> request(grantType), description);

        assertRefusal(refused, scripted, leg, reason);
    }

    /**
     * The record names three legs, and {@code other} is reached only by a request whose
     * {@code grant_type} is outside the allow-list. No gateway flow issues such a request, so the
     * value is proven here, by driving the client directly.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("requestsOutsideTheGrantTypeAllowList")
    @DisplayName("Should record the leg 'other' for a request whose grant_type is not on the allow-list")
    void shouldRecordTheLegOtherOutsideTheAllowList(String description, Map<String, String> form) {
        Scripted scripted = script(TYPE_BEARER, AccessTokenShape.BOUND);
        String tokenEndpoint = stub.url(StubIdentityProvider.Endpoint.TOKEN);

        RedeemedResponseException refused = assertThrows(RedeemedResponseException.class,
                () -> client.requestToken(tokenEndpoint, form, Map.of(), senderConstraint), description);

        assertRefusal(refused, scripted, LEG_OTHER, REASON_TOKEN_TYPE);
    }

    @Test
    @DisplayName("Should refuse a call made without a sender constraint — its response cannot be bound")
    void shouldRefuseACallMadeWithoutASenderConstraint() {
        Scripted scripted = script(TYPE_BEARER, AccessTokenShape.WITHOUT_CNF);
        String tokenEndpoint = stub.url(StubIdentityProvider.Endpoint.TOKEN);
        Map<String, String> form = form(REFRESH_TOKEN);

        RedeemedResponseException refused = assertThrows(RedeemedResponseException.class,
                () -> client.requestToken(tokenEndpoint, form, Map.of()));

        assertEquals(Optional.empty(),
                stub.received(StubIdentityProvider.Endpoint.TOKEN).getFirst().header(DPOP_HEADER),
                "the three-argument overload sends no proof, and is judged by the same check");
        assertRefusal(refused, scripted, LEG_REFRESH, REASON_TOKEN_TYPE);
    }

    @Test
    @DisplayName("Should leave a non-success answer to the engine — invalid_grant is no refusal of this class")
    void shouldLeaveANonSuccessAnswerToTheEngine() {
        CredentialRejectedException rejected =
                assertThrows(CredentialRejectedException.class, () -> request(REFRESH_TOKEN));

        assertAll("only a success answer is judged",
                () -> assertEquals(RefreshFailureClassification.Kind.CREDENTIAL_REJECTED,
                        RefreshFlow.classify(rejected).kind(),
                        "the engine's own classification of the stub's default 400 stands"),
                () -> assertEquals(List.of(), refusalRecords(), "no binding refusal is recorded"));
    }

    @Test
    @DisplayName("Should accept a bound response after the engine's one DPoP-Nonce retry")
    void shouldAcceptABoundResponseAfterTheNonceRetry() throws Exception {
        String nonce = Generators.letterStrings(16, 24).next();
        stub.script(StubIdentityProvider.Endpoint.TOKEN, new StubIdentityProvider.Answer(400,
                Map.of(DPOP_NONCE_HEADER, nonce, "Content-Type", "application/json"),
                "{\"error\":\"use_dpop_nonce\"}"));
        Scripted scripted = script(TYPE_DPOP, AccessTokenShape.BOUND);

        TokenResponse response = request(AUTHORIZATION_CODE);

        List<StubIdentityProvider.ReceivedRequest> received = stub.received(StubIdentityProvider.Endpoint.TOKEN);
        assertEquals(2, received.size(), "the challenge is answered by exactly one retry");
        JsonNode firstProof = proofClaims(received.getFirst());
        JsonNode retryProof = proofClaims(received.getLast());
        assertAll("the nonce retry stays the engine's, and its answer is judged like any other",
                () -> assertTrue(firstProof.path("nonce").isMissingNode(), "the first proof carries no nonce"),
                () -> assertEquals(nonce, retryProof.path("nonce").asText(), "the retry echoes the challenge"),
                () -> assertEquals(scripted.accessToken(), response.accessToken),
                () -> assertEquals(List.of(), refusalRecords(), "the challenge itself is not a refusal"));
    }

    @ParameterizedTest(name = "expected thumbprint ''{0}''")
    @ValueSource(strings = {"", " ", "\t"})
    @DisplayName("Should refuse to be built with a blank expected thumbprint")
    void shouldRefuseABlankExpectedThumbprint(String blank) {
        ClientConfiguration configuration = configuration();

        assertThrows(IllegalArgumentException.class, () -> new BoundTokenEndpointClient(configuration, blank),
                "a blank comparand would accept an access token whose jkt is blank");
    }

    /**
     * Asserts the whole refusal contract: the failure is classified as a redeemed grant, exactly one
     * {@code WARN} record names the leg and the reason, the exception names the reason, and neither a
     * record of any level nor the exception carries a scripted value.
     */
    private void assertRefusal(RedeemedResponseException refused, Scripted scripted, String leg, String reason) {
        List<LogRecord> refusals = refusalRecords();
        assertEquals(1, refusals.size(), "the refusal is recorded exactly once");
        String recorded = String.valueOf(refusals.getFirst().getMessage());
        String thrown = String.valueOf(refused.getMessage());
        assertAll("a refused token response",
                () -> assertEquals(RefreshFailureClassification.Kind.REDEEMED, RefreshFlow.classify(refused).kind(),
                        "a refusal after a success answer is a grant the identity provider redeemed"),
                () -> assertTrue(recorded.contains("on the " + leg + " leg"), "the record names the leg: " + recorded),
                () -> assertTrue(recorded.contains("(" + reason + ")"), "the record names the reason: " + recorded),
                () -> assertTrue(thrown.contains(reason), "the exception names the reason: " + thrown));
        assertNothingScriptedIsDisclosed(refused, scripted);
    }

    /**
     * Asserts that no captured record, at any level, and no message of the refusal or of a cause it
     * chains carries the scripted access token, refresh token, ID token or {@code jkt}.
     */
    private static void assertNothingScriptedIsDisclosed(Throwable refused, Scripted scripted) {
        List<LogRecord> records = TestLoggerFactory.getTestHandler().getRecords();
        assertFalse(records.isEmpty(), "no record was captured at all, so the absence would prove nothing");
        List<String> secrets = scripted.secrets();
        List<Executable> checks = new ArrayList<>();
        for (LogRecord captured : records) {
            String rendered = rendered(captured);
            checks.add(() -> assertTrue(secrets.stream().noneMatch(rendered::contains),
                    "a " + captured.getLevel() + " record of " + captured.getLoggerName()
                            + " carries scripted token material"));
        }
        for (Throwable cause = refused; cause != null; cause = cause.getCause()) {
            String message = String.valueOf(cause.getMessage());
            checks.add(() -> assertTrue(secrets.stream().noneMatch(message::contains),
                    "the refusal carries scripted token material: " + message));
        }
        assertAll("nothing the identity provider issued is disclosed", checks);
    }

    private static String rendered(LogRecord captured) {
        StringBuilder rendered = new StringBuilder(String.valueOf(captured.getMessage()));
        for (Throwable thrown = captured.getThrown(); thrown != null; thrown = thrown.getCause()) {
            rendered.append('\n').append(thrown.getMessage());
        }
        return rendered.toString();
    }

    private static List<LogRecord> refusalRecords() {
        return TestLoggerFactory.getTestHandler().resolveLogMessagesContaining(TestLogLevel.WARN,
                BffLogMessages.WARN.TOKEN_RESPONSE_NOT_BOUND.resolveIdentifierString());
    }

    private TokenResponse request(String grantType) {
        return client.requestToken(stub.url(StubIdentityProvider.Endpoint.TOKEN), form(grantType), Map.of(),
                senderConstraint);
    }

    private static Map<String, String> form(String grantType) {
        return AUTHORIZATION_CODE.equals(grantType)
                ? Map.of(GRANT_TYPE, grantType, "code", Generators.letterStrings(16, 24).next())
                : Map.of(GRANT_TYPE, grantType, REFRESH_TOKEN, Generators.letterStrings(16, 24).next());
    }

    /** Scripts one success answer of the token endpoint and returns the values it carries. */
    private Scripted script(String tokenType, AccessTokenShape shape) {
        Scripted scripted = scripted(shape);
        ObjectNode body = JSON.createObjectNode()
                .put("access_token", scripted.accessToken())
                .put("token_type", tokenType)
                .put("expires_in", 300)
                .put("refresh_token", scripted.refreshToken())
                .put("id_token", scripted.idToken());
        stub.script(StubIdentityProvider.Endpoint.TOKEN, StubIdentityProvider.Answer.json(200, body.toString()));
        return scripted;
    }

    private Scripted scripted(AccessTokenShape shape) {
        String subject = Generators.letterStrings(8, 16).next();
        String refreshToken = Generators.letterStrings(24, 32).next();
        String idToken = compactJws("{\"sub\":\"%s\",\"nonce\":\"%s\"}".formatted(subject,
                Generators.letterStrings(8, 16).next()));
        return switch (shape) {
            case BOUND -> new Scripted(accessTokenBoundTo(subject, proofGenerator.jkt()), refreshToken, idToken,
                    Optional.of(proofGenerator.jkt()));
            case BOUND_TO_ANOTHER_KEY -> new Scripted(accessTokenBoundTo(subject, otherKeyThumbprint), refreshToken,
                    idToken, Optional.of(otherKeyThumbprint));
            case WITHOUT_CNF -> new Scripted(compactJws("{\"sub\":\"%s\"}".formatted(subject)), refreshToken,
                    idToken, Optional.empty());
            case CNF_WITHOUT_JKT -> new Scripted(
                    compactJws("{\"sub\":\"%s\",\"cnf\":{\"x5t#S256\":\"%s\"}}".formatted(subject,
                            Generators.letterStrings(16, 24).next())),
                    refreshToken, idToken, Optional.empty());
            case OPAQUE -> new Scripted("opaque-" + Generators.letterStrings(24, 32).next(), refreshToken, idToken,
                    Optional.empty());
            case PAYLOAD_NOT_JSON -> new Scripted(
                    encode("{\"alg\":\"RS256\"}") + "." + encode("not json " + subject) + "." + encode("signature"),
                    refreshToken, idToken, Optional.empty());
        };
    }

    private static String accessTokenBoundTo(String subject, String thumbprint) {
        return compactJws("{\"sub\":\"%s\",\"cnf\":{\"jkt\":\"%s\"}}".formatted(subject, thumbprint));
    }

    /** A three-segment compact JWS over {@code payloadJson}; the signature segment is not a signature. */
    private static String compactJws(String payloadJson) {
        return encode("{\"alg\":\"RS256\",\"typ\":\"at+jwt\"}") + "." + encode(payloadJson) + "."
                + encode("signature");
    }

    private static String encode(String value) {
        return BASE64_URL.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** The claims of the DPoP proof a recorded token request carried. */
    private static JsonNode proofClaims(StubIdentityProvider.ReceivedRequest request) throws IOException {
        String proof = request.header(DPOP_HEADER).orElseThrow(
                () -> new AssertionError("the token request carries no DPoP header"));
        return JSON.readTree(Base64.getUrlDecoder().decode(proof.split("\\.")[1]));
    }

    /**
     * The client's own back-channel configuration: it reaches the stub over TLS because its SSL
     * context trusts the stub's root certificate, with hostname verification at its default.
     */
    private ClientConfiguration configuration() {
        return ClientConfiguration.builder()
                .issuer(stub.issuer())
                .clientId("gateway-client")
                .authMethod(ClientAuthMethod.PRIVATE_KEY_JWT)
                .scopes(List.of("openid"))
                .redirectUri("https://gw.example.com/auth/callback")
                .sslContext(TestTlsConfigurationRegistry.withAnchor(PROFILE, stub.rootCertificate()).profileContext())
                .build();
    }
}
