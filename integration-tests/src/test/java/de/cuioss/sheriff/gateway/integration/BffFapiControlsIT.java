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
package de.cuioss.sheriff.gateway.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the FAPI 2.0 controls of the key-authenticated gateway instances against the live compose
 * stack and the native image: pushed authorization requests, key-based client authentication, the
 * published client key, and the DPoP sender constraint. Every key in play here is <em>provided</em> —
 * read from the committed files under {@code signing-keys/}.
 * <p>
 * <strong>Each control is tested with a control of its own.</strong>
 * <ul>
 *   <li><em>Pushed request.</em> A hand-built front-channel authorization request for
 *       {@code integration-client} that carries its parameters in the URL and no {@code request_uri}
 *       is refused by Keycloak, while the gateway's own redirect completes a login. The matched pair
 *       shows that the realm <em>enforces</em> the push and does not merely tolerate it.</li>
 *   <li><em>Client authentication.</em> A token request that presents a client secret for
 *       {@code integration-client} is refused by Keycloak. The control is the token-mint client: the
 *       same endpoint accepts a secret from a client registered for one, so the refusal is of this
 *       client's credential and not of the endpoint.</li>
 *   <li><em>Published key.</em> {@code GET /auth/jwks} on the primary instance, with no credential,
 *       answers one key that carries {@code use: sig}, the algorithm {@code PS256} and no private
 *       member, and whose {@code kid} equals the RFC 7638 thumbprint this test computes from the public
 *       block of the committed client key file. The same request with {@code POST} answers
 *       {@code 405}.</li>
 *   <li><em>Sender constraint.</em> The access token the gateway mediates to the echo upstream carries
 *       {@code cnf.jkt}, arrives as {@code Authorization: Bearer} with no proof beside it, and its
 *       {@code jkt} equals the thumbprint this test computes from the committed DPoP key file — and
 *       differs from the published client key id. Both proof key types are covered: the primary
 *       instance (EC, {@code ES256}) and the cookie-refresh instance (RSA, {@code PS256}), where the
 *       token rotated inside the near-expiry window carries the same {@code jkt}. The vacuity control
 *       is a token minted through {@code token-mint-client}, which carries no {@code cnf} claim: the
 *       claim is there because the gateway presented a proof, not because the realm adds it to every
 *       token.</li>
 * </ul>
 * <p>
 * <strong>The binding check.</strong> Every gateway refuses a token response that is not bound to its
 * own proof key: the login is refused, or the session is ended. The logins and the rotation driven
 * here complete, so they are the proof in key mode that Keycloak's bound response is accepted on the
 * authorization-code exchange and on the refresh grant. The rotation leg therefore also reads the log
 * of the instance that rotated: it must have gained a token-refreshed record
 * ({@value #TOKEN_REFRESHED_RECORD}), which is the control that the log was read and the rotation ran,
 * and it must carry no record of a refused token response ({@value #TOKEN_NOT_BOUND_RECORD}).
 * <p>
 * <strong>What this suite does NOT prove.</strong> It does not show a token response being
 * <em>refused</em>: Keycloak binds every access token of these clients, so the refusal is proven at unit level
 * against a scripted identity provider ({@code BoundTokenEndpointClientTest},
 * {@code BffRuntimeProducerTest}). It does not prove that an upstream ignores {@code cnf} — the echo
 * upstream checks nothing. The client-secret mode is {@code BffClientSecretModeIT}'s, and generated
 * keys are {@code BffGeneratedKeysIT}'s. Like every {@code Bff*IT} it replays a cookie map and asserts
 * nothing about browser cookie policy (see {@link BffKeycloakLoginFlow}).
 * <p>
 * The helpers that read a token, compute a thumbprint, read an instance log or drive a hand-built
 * authorization request are package-private, so the two sibling suites state the same facts the same
 * way.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("FAPI 2.0 controls in key mode: pushed requests, private_key_jwt, the published key and the DPoP binding")
class BffFapiControlsIT {

    /** The require:session route that mediates a bearer to the go-httpbin echo upstream. */
    static final String MEDIATED_PATH = "/bff-session/get";

    /**
     * INFO — the mediated tokens of a session were refreshed. The trailing colon is part of the match:
     * a log line renders the identifier as {@code ApiSheriff-12:}, and without it the value is a prefix
     * of every identifier from 120 to 129.
     */
    static final String TOKEN_REFRESHED_RECORD = "ApiSheriff-12:";

    /** WARN — a token response was refused because it is not bound to the gateway's proof key. */
    static final String TOKEN_NOT_BOUND_RECORD = "ApiSheriff-131";

    /**
     * Seconds to wait before a call that must land inside the near-expiry window of a 45-second
     * token: the window opens at {@code lifespan - leeway} = 15s, and 22 sits inside it with margin at
     * both ends, as in {@code BffTokenRefreshIT}.
     */
    static final int WAIT_INTO_REFRESH_WINDOW_SECONDS = 22;

    /** The gateway's default client JWKS path, which the primary instance publishes its key at. */
    static final String JWKS_PATH = "/auth/jwks";

    /** The client of the primary instance (see {@code sheriff-config/gateway.yaml}). */
    private static final String CLIENT_ID = "integration-client";

    /**
     * The secret {@code integration-client} was registered with before it was moved to a key. The realm
     * import declares no secret for the client any more; presenting the former one is what a
     * deployment that kept an old descriptor would do.
     */
    private static final String FORMER_CLIENT_SECRET = "integration-secret";

    private static final String COOKIE_REFRESH_LOG = "quarkus-cookie-refresh.log";

    private static final String KEYCLOAK_REALM =
            "https://" + BffKeycloakLoginFlow.KEYCLOAK_HOST_AUTHORITY + "/realms/integration";

    /** The members of a JWK that would disclose an RSA or EC private key. */
    private static final List<String> PRIVATE_JWK_MEMBERS = List.of("d", "p", "q", "dp", "dq", "qi", "oth");

    private static final long LOG_VISIBILITY_TIMEOUT_MILLIS = 5_000L;
    private static final long LOG_POLL_INTERVAL_MILLIS = 250L;

    @Test
    @DisplayName("pushed request: a front-channel request without request_uri is refused, the gateway's own redirect completes a login")
    void frontChannelRequestIsRefusedWhileThePushedRequestCompletesALogin() {
        assertFrontChannelRequestRefused(CLIENT_ID, BffKeycloakLoginFlow.GATEWAY_ORIGIN + "/auth/callback");

        Session session = BffKeycloakLoginFlow.login(MEDIATED_PATH);

        Response mediated = mediatedCall(session.gatewayCookies(), BffKeycloakLoginFlow.GATEWAY_ORIGIN);
        assertEquals("GET", mediated.path("method"),
                "the login the gateway started with a pushed request must complete and mediate a token; "
                        + "with the front-channel request refused above, the realm enforces the push");
    }

    @Test
    @DisplayName("client authentication: a client secret is refused for the key-authenticated client, and accepted from the token-mint client")
    void clientSecretIsRefusedForTheKeyAuthenticatedClient() {
        Response refused = BffKeycloakLoginFlow.keycloak(Map.of())
                .contentType(ContentType.URLENC)
                .formParam("grant_type", "client_credentials")
                .formParam("client_id", CLIENT_ID)
                .formParam("client_secret", FORMER_CLIENT_SECRET)
                .when().post(KEYCLOAK_REALM + "/protocol/openid-connect/token")
                .then().extract().response();

        String control = BearerValidationIT.mintIntegrationRealmAccessToken();

        assertAll("a secret does not authenticate a client registered for a signed JWT",
                () -> assertTrue(refused.statusCode() == 400 || refused.statusCode() == 401,
                        () -> "Keycloak must refuse the token request, answered " + refused.statusCode() + ": "
                                + refused.asString()),
                () -> assertNull(refused.path("access_token"), "a refused request must issue no token"),
                () -> assertNotNull(refused.path("error"),
                        () -> "the refusal must name an OAuth error: " + refused.asString()),
                () -> assertFalse(control.isBlank(),
                        "control: the same endpoint issues a token to a client that is registered for a secret"));
    }

    @Test
    @DisplayName("published key: GET /auth/jwks answers the one public client key under its thumbprint, POST answers 405")
    void jwksEndpointPublishesTheClientKeyAndNothingElse() throws Exception {
        Response answered = BffKeycloakLoginFlow.gateway(Map.of())
                .when().get(JWKS_PATH)
                .then().statusCode(200)
                .extract().response();
        Response posted = BffKeycloakLoginFlow.gateway(Map.of())
                .when().post(JWKS_PATH)
                .then().extract().response();

        Map<String, Object> key = soleKey(answered);
        String expectedKeyId = publicKeyThumbprint(signingKeyFile("client-auth-rsa.pem"));
        assertAll("the key set the identity provider fetches",
                () -> assertEquals("sig", key.get("use"), "the key is published for signatures"),
                () -> assertEquals("PS256", key.get("alg"), "an RSA client key signs PS256"),
                () -> assertEquals("RSA", key.get("kty")),
                () -> assertEquals(expectedKeyId, key.get("kid"),
                        "the key id must be the RFC 7638 thumbprint of the committed client key"),
                () -> assertEquals(expectedKeyId, jwkThumbprint(key),
                        "and the published members must be those of that key"),
                () -> assertFalse(key.containsKey("key_ops"), "the key carries use, not key_ops"),
                () -> assertEquals(List.of(), PRIVATE_JWK_MEMBERS.stream().filter(key::containsKey).toList(),
                        "no private member may be published"),
                () -> assertEquals(405, posted.statusCode(), "the endpoint answers GET only"));
    }

    @Test
    @DisplayName("sender constraint: the mediated token is bound to the EC proof key and forwarded as a plain bearer")
    void mediatedTokenIsBoundToTheEcProofKeyAndForwardedAsBearer() throws Exception {
        Session session = BffKeycloakLoginFlow.login(MEDIATED_PATH);

        Response mediated = mediatedCall(session.gatewayCookies(), BffKeycloakLoginFlow.GATEWAY_ORIGIN);

        String token = bearerToken(mediated);
        String publishedKeyId = String.valueOf(soleKey(BffKeycloakLoginFlow.gateway(Map.of())
                .when().get(JWKS_PATH).then().statusCode(200).extract().response()).get("kid"));
        Map<String, Object> forwardedHeaders = mediated.path("headers");
        assertAll("a token bound at the identity provider, relayed without a proof",
                () -> assertEquals(publicKeyThumbprint(signingKeyFile("dpop-ec.pem")), confirmationThumbprint(token),
                        "cnf.jkt must be the thumbprint of the committed EC proof key"),
                () -> assertNotEquals(publishedKeyId, confirmationThumbprint(token),
                        "the proof key is not the client-authentication key"),
                () -> assertEquals(List.of(), forwardedHeaders.keySet().stream()
                                .filter(name -> "DPoP".equalsIgnoreCase(name)).toList(),
                        "the gateway presents its proof to the identity provider only, never upstream"));
    }

    @Test
    @DisplayName("sender constraint: the rotated token of the cookie-refresh instance stays bound to the RSA proof key")
    void rotatedTokenStaysBoundToTheRsaProofKey() throws Exception {
        String origin = BffKeycloakLoginFlow.COOKIE_REFRESH_GATEWAY_ORIGIN;
        Session session = BffKeycloakLoginFlow.login(MEDIATED_PATH, origin,
                BffKeycloakLoginFlow.REFRESH_USERNAME, BffKeycloakLoginFlow.REFRESH_PASSWORD);
        String atLogin = bearerToken(mediatedCall(session.gatewayCookies(), origin));
        long refreshedBefore = recordCount(COOKIE_REFRESH_LOG, TOKEN_REFRESHED_RECORD);

        sleepSeconds(WAIT_INTO_REFRESH_WINDOW_SECONDS);
        String rotated = bearerToken(mediatedCall(session.gatewayCookies(), origin));

        String proofKey = publicKeyThumbprint(signingKeyFile("dpop-rsa.pem"));
        assertNotEquals(atLogin, rotated, "the call inside the near-expiry window must mediate a rotated token");
        assertAll("both token legs are bound to the RSA proof key",
                () -> assertEquals(proofKey, confirmationThumbprint(atLogin),
                        "the token of the code exchange must carry the thumbprint of the committed RSA proof key"),
                () -> assertEquals(proofKey, confirmationThumbprint(rotated),
                        "the token of the refresh grant must carry the same thumbprint"));
        assertRecordCountAbove(COOKIE_REFRESH_LOG, TOKEN_REFRESHED_RECORD, refreshedBefore,
                "the rotation must be recorded, or the absence asserted next was read from a log that shows nothing");
        assertEquals(0, recordCount(COOKIE_REFRESH_LOG, TOKEN_NOT_BOUND_RECORD),
                "the gateway accepted Keycloak's bound responses on both legs, so it recorded no refusal");
    }

    @Test
    @DisplayName("vacuity control: a token minted outside the gateway carries no cnf claim")
    void tokenMintedWithoutAProofCarriesNoConfirmationClaim() {
        String token = BearerValidationIT.mintIntegrationRealmAccessToken();

        assertNull(claims(token).get("cnf"),
                "the realm must not add cnf to a token requested without a proof, otherwise its presence "
                        + "in a mediated token says nothing about the gateway");
    }

    // ---------------------------------------------------------------- shared helpers

    /**
     * Sends a complete authorization request for {@code clientId} through the browser's channel — its
     * parameters in the URL, no {@code request_uri} — and asserts that Keycloak refuses it.
     * <p>
     * The request is otherwise valid: a registered redirect URI, a response type, a scope, a state, a
     * nonce and a PKCE challenge. Keycloak answers a refused authorization request in one of two
     * shapes, and both are accepted: a {@code 302} to the redirect URI that carries
     * {@code error=invalid_request} and no {@code code}, or an error page with a {@code 4xx} status.
     * What is never accepted is a login form, which is how a request that was <em>admitted</em> is
     * answered.
     *
     * @param clientId    the realm client the request is made for
     * @param redirectUri a redirect URI the realm accepts for that client
     */
    static void assertFrontChannelRequestRefused(String clientId, String redirectUri) {
        String request = KEYCLOAK_REALM + "/protocol/openid-connect/auth"
                + "?response_type=code"
                + "&client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode("openid profile email")
                + "&response_mode=query"
                + "&state=" + UUID.randomUUID()
                + "&nonce=" + UUID.randomUUID()
                + "&code_challenge=" + sha256Base64Url(UUID.randomUUID().toString())
                + "&code_challenge_method=S256";

        Response response = BffKeycloakLoginFlow.keycloak(Map.of())
                .redirects().follow(false)
                .when().get(request)
                .then().extract().response();

        String location = response.getHeader("Location");
        String observed = "status " + response.statusCode() + ", Location " + location;
        assertFalse(response.asString().contains("login-actions/authenticate"),
                () -> "Keycloak answered a front-channel request of " + clientId + " with its login form, so "
                        + "the realm does not require the push (" + observed + ")");
        if (response.statusCode() == 302) {
            assertNotNull(location, "a redirect must carry a Location");
            Map<String, String> parameters = queryParameters(location);
            assertAll("the refusal Keycloak redirects back to the client (" + observed + ")",
                    () -> assertTrue(location.startsWith(redirectUri),
                            "a refused request is answered to the client's redirect URI, not to a login page"),
                    () -> assertEquals("invalid_request", parameters.get("error")),
                    () -> assertFalse(parameters.containsKey("code"), "a refused request must issue no code"));
        } else {
            assertTrue(response.statusCode() >= 400 && response.statusCode() < 500,
                    () -> "a front-channel request of " + clientId + " must be refused (" + observed + ")");
        }
    }

    /** A mediated call that must be served, on the given gateway instance. */
    static Response mediatedCall(Map<String, String> gatewayCookies, String origin) {
        return BffKeycloakLoginFlow.gateway(gatewayCookies, origin)
                .redirects().follow(false)
                .when().get(MEDIATED_PATH)
                .then().statusCode(200)
                .extract().response();
    }

    /**
     * The access token the gateway relayed, read from the echo of a mediated call. The upstream must
     * have received exactly one {@code Authorization} value, of the {@code Bearer} scheme: the binding
     * ends at the gateway, so the token travels on as a plain bearer token.
     */
    static String bearerToken(Response echoed) {
        List<String> authorization = echoed.jsonPath().getList("headers.Authorization", String.class);
        assertNotNull(authorization, "the session must mediate a token to the upstream");
        assertEquals(1, authorization.size(), () -> "exactly one Authorization value is relayed: " + authorization);
        String value = authorization.getFirst();
        assertTrue(value.startsWith("Bearer "),
                "the gateway relays the bound token under the Bearer scheme, with no proof");
        return value.substring("Bearer ".length());
    }

    /** The claims of an access token; the token is read, not verified. */
    static JsonPath claims(String token) {
        String[] segments = token.split("\\.");
        assertEquals(3, segments.length, "an access token must be a three-segment JWS");
        return new JsonPath(new String(Base64.getUrlDecoder().decode(segments[1]), StandardCharsets.UTF_8));
    }

    /** The {@code cnf.jkt} of an access token, which must be present. */
    static String confirmationThumbprint(String token) {
        String thumbprint = claims(token).getString("cnf.jkt");
        assertNotNull(thumbprint, "the access token must carry cnf.jkt — it is not bound to a proof key");
        return thumbprint;
    }

    /** A committed signing-key file of the stack. */
    static Path signingKeyFile(String name) {
        return OneOffGatewayContainers.DOCKER.resolve(Path.of("signing-keys", name));
    }

    /**
     * The RFC 7638 thumbprint of the key in the {@code PUBLIC KEY} block of a signing-key file,
     * computed here from the key itself and independent of anything the gateway reports.
     */
    static String publicKeyThumbprint(Path keyFile) throws IOException {
        String pem = Files.readString(keyFile);
        int begin = pem.indexOf("-----BEGIN PUBLIC KEY-----");
        int end = pem.indexOf("-----END PUBLIC KEY-----");
        assertTrue(begin >= 0 && end > begin, () -> keyFile + " must hold a PUBLIC KEY block");
        byte[] encoded = Base64.getMimeDecoder()
                .decode(pem.substring(begin + "-----BEGIN PUBLIC KEY-----".length(), end));
        Map<String, Object> jwk = new LinkedHashMap<>();
        switch (publicKey(encoded)) {
            case RSAPublicKey rsa -> {
                jwk.put("kty", "RSA");
                jwk.put("n", base64Url(unsigned(rsa.getModulus(), 0)));
                jwk.put("e", base64Url(unsigned(rsa.getPublicExponent(), 0)));
            }
            case ECPublicKey ec -> {
                assertEquals(256, ec.getParams().getCurve().getField().getFieldSize(),
                        "the stack's EC keys are on P-256");
                jwk.put("kty", "EC");
                jwk.put("crv", "P-256");
                jwk.put("x", base64Url(unsigned(ec.getW().getAffineX(), 32)));
                jwk.put("y", base64Url(unsigned(ec.getW().getAffineY(), 32)));
            }
            default -> throw new AssertionError(keyFile + " holds neither an RSA nor an EC public key");
        }
        return jwkThumbprint(jwk);
    }

    /**
     * The RFC 7638 thumbprint of a JWK: the SHA-256 of its required members, in lexicographic order,
     * as JSON without whitespace.
     */
    static String jwkThumbprint(Map<String, ?> jwk) {
        List<String> required = "RSA".equals(jwk.get("kty")) ? List.of("e", "kty", "n") : List.of("crv", "kty", "x", "y");
        Map<String, String> members = new TreeMap<>();
        for (String member : required) {
            Object value = jwk.get(member);
            assertNotNull(value, () -> "the key carries no '" + member + "' member: " + jwk.keySet());
            members.put(member, value.toString());
        }
        String canonical = members.entrySet().stream()
                .map(member -> "\"" + member.getKey() + "\":\"" + member.getValue() + "\"")
                .collect(Collectors.joining(",", "{", "}"));
        return sha256Base64Url(canonical);
    }

    /** The one key of a published key set. */
    static Map<String, Object> soleKey(Response keySet) {
        List<Map<String, Object>> keys = new JsonPath(keySet.asString()).getList("keys");
        assertNotNull(keys, () -> "the answer is no key set: " + keySet.asString());
        assertEquals(1, keys.size(), "exactly one key is published");
        return keys.getFirst();
    }

    /** The number of lines of an instance log that carry {@code record}. */
    static long recordCount(String logFileName, String record) {
        Path logFile = Path.of(System.getProperty("test.log.dir", "target/quarkus-logs")).resolve(logFileName);
        assertTrue(Files.isRegularFile(logFile), () -> "expected the instance log at " + logFile.toAbsolutePath());
        try {
            return Files.readString(logFile, StandardCharsets.UTF_8).lines()
                    .filter(line -> line.contains(record)).count();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + logFile, e);
        }
    }

    /**
     * Asserts that an instance log gained a record since {@code countBefore} was taken. The record is
     * written before the response is sent, but the bind mount can surface the append a moment later,
     * so the read is retried briefly.
     */
    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - bounded wait for a bind-mounted log append
    static void assertRecordCountAbove(String logFileName, String record, long countBefore, String why) {
        long deadline = System.currentTimeMillis() + LOG_VISIBILITY_TIMEOUT_MILLIS;
        long observed = recordCount(logFileName, record);
        while (observed <= countBefore && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(LOG_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for a log record", interrupted);
            }
            observed = recordCount(logFileName, record);
        }
        long finalObserved = observed;
        assertTrue(finalObserved > countBefore, () -> why + ": expected a new " + record + " line in " + logFileName
                + " (count before " + countBefore + ", after " + finalObserved + ")");
    }

    /**
     * Bounded wall-clock wait. The property under test is defined in elapsed time against a token
     * lifespan, so there is no condition to poll.
     */
    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - the token lifespan IS the clock under test
    static void sleepSeconds(long seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the refresh window", interrupted);
        }
    }

    private static Map<String, String> queryParameters(String location) {
        Map<String, String> parameters = new LinkedHashMap<>();
        String rawQuery = URI.create(location).getRawQuery();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                String[] nameValue = pair.split("=", 2);
                parameters.put(URLDecoder.decode(nameValue[0], StandardCharsets.UTF_8),
                        nameValue.length == 2 ? URLDecoder.decode(nameValue[1], StandardCharsets.UTF_8) : "");
            }
        }
        return parameters;
    }

    private static PublicKey publicKey(byte[] encoded) {
        X509EncodedKeySpec spec = new X509EncodedKeySpec(encoded);
        try {
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (InvalidKeySpecException notRsa) {
            try {
                return KeyFactory.getInstance("EC").generatePublic(spec);
            } catch (GeneralSecurityException notEc) {
                throw new AssertionError("the PUBLIC KEY block is neither an RSA nor an EC key", notEc);
            }
        } catch (GeneralSecurityException e) {
            throw new AssertionError("no RSA key factory", e);
        }
    }

    /**
     * The big-endian magnitude of a non-negative integer: without a sign byte, and left-padded to
     * {@code length} bytes when {@code length} is positive — the octet form RFC 7518 prescribes.
     */
    private static byte[] unsigned(BigInteger value, int length) {
        byte[] bytes = value.toByteArray();
        int start = bytes.length > 1 && bytes[0] == 0 ? 1 : 0;
        int size = Math.max(bytes.length - start, length);
        byte[] magnitude = new byte[size];
        System.arraycopy(bytes, start, magnitude, size - (bytes.length - start), bytes.length - start);
        return magnitude;
    }

    private static String sha256Base64Url(String value) {
        try {
            return base64Url(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new AssertionError("no SHA-256", e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
