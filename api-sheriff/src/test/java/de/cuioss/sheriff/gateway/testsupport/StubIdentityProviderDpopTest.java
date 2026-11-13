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
package de.cuioss.sheriff.gateway.testsupport;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.cuioss.sheriff.gateway.auth.TestTlsConfigurationRegistry;
import de.cuioss.sheriff.gateway.testsupport.StubIdentityProvider.Answer;
import de.cuioss.sheriff.gateway.testsupport.StubIdentityProvider.Endpoint;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the DPoP check of {@link StubIdentityProvider}: a request carrying a {@code DPoP} header
 * reaches the endpoint's answer only when the proof's {@code htu} names the URL the request was
 * received on and its {@code htm} the request method, as Keycloak judges a proof. Every other proof is
 * answered {@code 400} {@code invalid_dpop_proof} without consuming the endpoint's script.
 * <p>
 * The requests are sent with {@link HttpClient} over TLS, trusting the stub's own root certificate.
 * The proofs are unsigned compact JWS strings built here: the stub judges the binding to the request,
 * never the signature.
 */
@EnableGeneratorController
@DisplayName("StubIdentityProvider — a DPoP proof must name the request it arrived on")
class StubIdentityProviderDpopTest {

    private static final String PROFILE = "stub-idp";
    private static final String DPOP_HEADER = "DPoP";
    private static final String POST = "POST";
    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String INVALID_DPOP_PROOF = "invalid_dpop_proof";
    private static final String URL_MISMATCH = "DPoP HTTP URL mismatch";
    private static final String METHOD_MISMATCH = "DPoP HTTP method mismatch";
    private static final String ADVERTISED_ISSUER = "https://idp.example.invalid/realms/demo";
    private static final String DEMO_REALM_PATH = "/realms/demo";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private StubIdentityProvider stub;

    @AfterEach
    void stopStub() {
        if (stub != null) {
            stub.close();
        }
    }

    @Test
    @DisplayName("Should serve the scripted answer when the proof names the received URL and method")
    void shouldServeTheScriptedAnswerForAMatchingProof() throws Exception {
        stub = StubIdentityProvider.start();
        String accessToken = Generators.letterStrings(24, 32).next();
        stub.script(Endpoint.TOKEN, Answer.json(200, "{\"access_token\":\"%s\"}".formatted(accessToken)));
        String tokenUrl = stub.origin() + "/token";

        HttpResponse<String> response = post(tokenUrl, proof(tokenUrl, POST));

        assertAll("a matching proof is passed through to the endpoint's script",
                () -> assertEquals(200, response.statusCode()),
                () -> assertEquals(accessToken, JSON.readTree(response.body()).path("access_token").asText()),
                () -> assertEquals(1, stub.received(Endpoint.TOKEN).size(), "the request is recorded"));
    }

    @Test
    @DisplayName("Should reject a proof that names the advertised URL while the request arrived on the origin")
    void shouldRejectAMismatchedProof() throws Exception {
        stub = StubIdentityProvider.startAdvertising(ADVERTISED_ISSUER);
        String accessToken = Generators.letterStrings(24, 32).next();
        stub.script(Endpoint.TOKEN, Answer.json(200, "{\"access_token\":\"%s\"}".formatted(accessToken)));
        String dialledUrl = stub.origin() + DEMO_REALM_PATH + "/token";
        String advertisedUrl = stub.url(Endpoint.TOKEN);

        HttpResponse<String> refused = post(dialledUrl, proof(advertisedUrl, POST));
        HttpResponse<String> next = post(dialledUrl, null);

        assertAll("a proof signed for the advertised URL is refused, and the script is left in place",
                () -> assertEquals(ADVERTISED_ISSUER + "/token", advertisedUrl, "the 0.9.7 proof shape"),
                () -> assertInvalidDpopProof(refused, URL_MISMATCH),
                () -> assertEquals(200, next.statusCode(), "the scripted answer is still queued"),
                () -> assertEquals(accessToken, JSON.readTree(next.body()).path("access_token").asText()),
                () -> assertEquals(2, stub.received(Endpoint.TOKEN).size(), "both requests are recorded"));
    }

    @Test
    @DisplayName("Should reject a proof whose htm is not the request method")
    void shouldRejectAMethodMismatch() throws Exception {
        stub = StubIdentityProvider.start();
        String tokenUrl = stub.origin() + "/token";

        HttpResponse<String> refused = post(tokenUrl, proof(tokenUrl, "GET"));

        assertInvalidDpopProof(refused, METHOD_MISMATCH);
    }

    @Test
    @DisplayName("Should reject a DPoP header whose payload does not decode to JSON")
    void shouldRejectAnUndecodableProof() throws Exception {
        stub = StubIdentityProvider.start();
        String tokenUrl = stub.origin() + "/token";
        String undecodable = encode("{\"typ\":\"dpop+jwt\"}") + "." + encode("not json "
                + Generators.letterStrings(8, 16).next()) + "." + encode("signature");

        HttpResponse<String> refused = post(tokenUrl, undecodable);

        assertInvalidDpopProof(refused, URL_MISMATCH);
    }

    @Test
    @DisplayName("Should answer a request without a DPoP header with the endpoint's default")
    void shouldLeaveARequestWithoutAProofToTheDefault() throws Exception {
        stub = StubIdentityProvider.start();

        HttpResponse<String> response = post(stub.origin() + "/token", null);

        assertAll("no proof, no check",
                () -> assertEquals(400, response.statusCode()),
                () -> assertEquals("invalid_grant", JSON.readTree(response.body()).path("error").asText(),
                        "the token endpoint's default refusal, not a proof refusal"));
    }

    @Test
    @DisplayName("Should reject a mismatched proof on the pushed-authorization-request endpoint as well")
    void shouldRejectAMismatchedProofOnAPeerEndpoint() throws Exception {
        stub = StubIdentityProvider.startAdvertising(ADVERTISED_ISSUER);
        String requestUri = "urn:ietf:params:oauth:request_uri:" + Generators.letterStrings(16, 24).next();
        stub.script(Endpoint.PUSHED_AUTHORIZATION_REQUEST,
                Answer.json(201, "{\"request_uri\":\"%s\",\"expires_in\":60}".formatted(requestUri)));
        String dialledUrl = stub.origin() + DEMO_REALM_PATH + "/par";

        HttpResponse<String> refused =
                post(dialledUrl, proof(stub.url(Endpoint.PUSHED_AUTHORIZATION_REQUEST), POST));
        HttpResponse<String> matching = post(dialledUrl, proof(dialledUrl, POST));

        assertAll("the check is not specific to the token endpoint",
                () -> assertInvalidDpopProof(refused, URL_MISMATCH),
                () -> assertEquals(201, matching.statusCode(), "the scripted push answer is still queued"),
                () -> assertEquals(requestUri, JSON.readTree(matching.body()).path("request_uri").asText()));
    }

    private static void assertInvalidDpopProof(HttpResponse<String> response, String description)
            throws IOException {
        JsonNode body = JSON.readTree(response.body());
        assertAll("answered as Keycloak answers a proof bound to another request",
                () -> assertEquals(400, response.statusCode()),
                () -> assertEquals(INVALID_DPOP_PROOF, body.path("error").asText()),
                () -> assertEquals(description, body.path("error_description").asText()));
    }

    /** Sends a form POST to {@code url}, with the given {@code DPoP} header when present. */
    private HttpResponse<String> post(String url, @Nullable String dpop) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", FORM)
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=authorization_code&code="
                        + Generators.letterStrings(16, 24).next()));
        if (dpop != null) {
            request.header(DPOP_HEADER, dpop);
        }
        try (HttpClient client = HttpClient.newBuilder()
                     .version(HttpClient.Version.HTTP_1_1)
                     .sslContext(TestTlsConfigurationRegistry.withAnchor(PROFILE, stub.rootCertificate()).profileContext())
                     .build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /** An unsigned compact JWS DPoP proof naming {@code htu} and {@code htm}. */
    private static String proof(String htu, String htm) {
        String claims = JSON.createObjectNode()
                .put("jti", Generators.letterStrings(16, 24).next())
                .put("htm", htm)
                .put("htu", htu)
                .put("iat", System.currentTimeMillis() / 1000)
                .toString();
        return encode("{\"typ\":\"dpop+jwt\",\"alg\":\"ES256\"}") + "." + encode(claims) + "." + encode("signature");
    }

    private static String encode(String value) {
        return BASE64_URL.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
