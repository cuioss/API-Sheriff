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

import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.CERTIFICATES;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.DOCKER;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.awaitReadiness;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.connectToNetwork;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.createNetwork;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.disconnectFromNetwork;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.docker;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.dockerQuietly;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.publishedPort;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.removeContainer;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.removeNetwork;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.startAuxiliaryContainer;
import static de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.startBffGateway;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.Yaml;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;
import de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.BffGateway;
import de.cuioss.sheriff.gateway.integration.OneOffGatewayContainers.ReadOnlyMount;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;

/**
 * A BFF gateway whose identity provider is a scripted, request-recording stub: the one fixture the
 * integration tests share that have to see, or to decide, what the identity provider is sent and what
 * it answers — the {@code scope} of a refresh grant, the number of refresh grants a burst of requests
 * produces, a token endpoint that fails.
 *
 * <h2>Why a WireMock container</h2>
 *
 * It is the one candidate that gives all three capabilities without new Java on any classpath: a
 * request journal the test reads back, scripted and sequenced answers, and fault injection (an error
 * status, a connection reset, a fixed delay). It is driven from the host over its admin API, exactly as
 * {@code PassthroughFaultIT} drives toxiproxy. A recording proxy in front of Keycloak cannot change
 * what Keycloak grants, so it cannot narrow a scope; a stub inside the test JVM would make the gateway
 * container dial back into the host.
 * <p>
 * The image is a test-only one, pinned in {@link #IMAGE} by release tag <em>and</em> digest. It is
 * started by this class alone and is named by no compose file and no production build input.
 *
 * <h2>The topology</h2>
 *
 * <ul>
 *   <li>A dedicated docker network, {@value #NETWORK}.</li>
 *   <li>The stub, {@value #STUB}, joins it under the alias {@value #IDENTITY_PROVIDER_ALIAS} and serves
 *       HTTPS on 8443 from a PKCS12 key store this class derives below {@code target/} from the
 *       committed {@code localhost.crt} and {@code localhost.key}. The subject alternative names of that
 *       certificate already cover the alias, so the gateway keeps
 *       {@code egress_tls.oidc_tls_profile: benchmark-idp}, keeps hostname verification on and needs no
 *       new trust material. Its plain-HTTP admin port is published on an ephemeral loopback port.</li>
 *   <li>The compose {@value #ECHO_SERVICE} container is connected to the network under its own name for
 *       the life of the rig, so the session route has its echo upstream, and disconnected on
 *       teardown.</li>
 *   <li>The gateway, {@value #GATEWAY}, joins that network only. Its descriptor is the committed
 *       {@code sheriff-config-refresh/gateway.yaml} with the gateway origin retargeted at the fixed
 *       loopback port {@value #APPLICATION_PORT}, written below {@code target/}; the signing-key
 *       directory is mounted, because that descriptor names a DPoP proof key.</li>
 * </ul>
 *
 * <h2>What the stub serves</h2>
 *
 * The provider metadata document below the issuer, the key set on every {@code jwks.url} path the
 * descriptor names, and the {@link Endpoint endpoints} that document names. Tokens are signed with an
 * RSA key generated when the rig starts, and every access token carries the thumbprint of the
 * committed proof key as {@code cnf.jkt}, because the gateway refuses a token response that is not
 * bound to its own proof key. The stub validates nothing: it does not check the client credential, the
 * authorization code, the PKCE verifier or the DPoP proof.
 * <p>
 * Each endpoint answers from its script when a test has {@linkplain #script(Endpoint, Answer) filled}
 * it, one scripted answer per request in the order they were added, and from its default otherwise.
 * The token endpoint refuses every grant by default ({@code 400}, {@code invalid_grant}): a token
 * answer carries a signed token with a lifetime, so a test states each one it expects with
 * {@link #tokenAnswer(Collection, Duration)}. A refresh nobody scripted therefore ends the session
 * instead of passing unnoticed.
 * <p>
 * <strong>Limit of the script.</strong> The sequencing is a WireMock scenario per endpoint. WireMock
 * matches a request and advances the scenario in two steps, so two requests that arrive at one endpoint
 * at the same instant can both be served the same scripted answer. A test that expects concurrent
 * requests at one endpoint must not rely on each receiving its own answer.
 *
 * <h2>The login</h2>
 *
 * {@link #login(String, Duration)} stands in for the browser and for the identity provider's login
 * page at once. It starts a login on the gateway, reads {@code state}, {@code nonce} and the requested
 * {@code scope} out of the pushed authorization request the stub recorded, scripts the token answer of
 * the code exchange, and calls the gateway callback itself. Like every {@code Bff*IT} helper it replays
 * a cookie map and applies no browser cookie policy.
 *
 * <h2>Lifecycle</h2>
 *
 * {@link #start()} removes what an aborted earlier run left behind, and removes everything it started
 * when it fails part-way. {@link #close()} removes the gateway, the stub and the network and
 * disconnects the echo container, on every exit path a try-with-resources block has.
 * <p>
 * A test-support class: named neither {@code *IT} nor {@code *Test}, so neither Failsafe nor Surefire
 * runs it. One rig at a time — the container names, the network name and the gateway port are fixed.
 * Safe to use from several threads once started: the journal is read from the stub, and
 * {@link #script(Endpoint, Answer)} is serialised.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
final class StubIdentityProviderRig implements AutoCloseable {

    /**
     * The stub's image: an exact release tag together with the digest of its multi-platform index, so
     * a moved tag cannot change what the suite runs.
     */
    static final String IMAGE =
            "wiremock/wiremock:3.13.1@sha256:d61e7720f89483fdef5366843b58d1dfd06bcce5828179c9f2f54de5c28354b0";

    /**
     * The fixed loopback host port of the gateway's application listener. The compose stack publishes
     * {@code 10443}–{@code 10455} and {@code BffGeneratedKeysIT} takes {@code 10456}.
     */
    static final int APPLICATION_PORT = 10457;

    /** The browser-facing origin of the rig's gateway. */
    static final String ORIGIN = "https://localhost:" + APPLICATION_PORT;

    /** The gateway container, for the log and readiness helpers of {@link OneOffGatewayContainers}. */
    static final String GATEWAY = "sheriff-stub-idp-gateway";

    /** The client the derived descriptor authenticates as. */
    static final String CLIENT_ID = "refresh-client";

    /** The subject of every token the stub issues. */
    static final String SUBJECT = "stub-identity-provider-user";

    private static final String NETWORK = "sheriff-stub-idp";
    private static final String STUB = "sheriff-stub-idp-wiremock";

    /** The name the descriptor's issuer and every {@code jwks.url} dial. */
    private static final String IDENTITY_PROVIDER_ALIAS = "keycloak";
    private static final String IDENTITY_PROVIDER_ORIGIN = "https://" + IDENTITY_PROVIDER_ALIAS + ":8443";
    private static final String REALM_PATH = "/realms/integration";
    private static final String ISSUER = IDENTITY_PROVIDER_ORIGIN + REALM_PATH;
    private static final String AUTHORIZATION_ENDPOINT = ISSUER + "/protocol/openid-connect/auth";

    /** The compose service the session routes relay to, and the name they dial it under. */
    private static final String ECHO_SERVICE = "go-httpbin";

    private static final Path SOURCE_DESCRIPTOR = DOCKER.resolve(Path.of("sheriff-config-refresh", "gateway.yaml"));

    /** The origin the committed refresh descriptor names, which the derivation retargets. */
    private static final String SOURCE_ORIGIN = BffKeycloakLoginFlow.REFRESH_GATEWAY_ORIGIN;

    /** Where the rig writes what it derives; a build output, never committed. */
    private static final Path WORK_DIRECTORY = Path.of("target", "stub-identity-provider");
    private static final Path DERIVED_DESCRIPTOR = WORK_DIRECTORY.resolve("gateway.yaml");
    private static final Path KEY_STORE_DIRECTORY = WORK_DIRECTORY.resolve("tls");
    private static final String KEY_STORE_FILE = "keystore.p12";
    private static final String KEY_STORE_MOUNT = "/stub-tls";

    /** Readable by the container users, which are not the uid the build runs as. */
    private static final String FILE_MODE = "rw-r--r--";
    private static final String DIRECTORY_MODE = "rwxr-xr-x";

    private static final String SIGNING_KEYS_MOUNT = "/app/signing-keys";
    private static final String PROOF_KEY_FILE = "dpop-rsa.pem";

    private static final int STUB_ADMIN_PORT = 8080;
    private static final int STUB_TLS_PORT = 8443;

    /** WireMock serves a stub with the lowest priority number first. */
    private static final int SCRIPTED_PRIORITY = 1;
    private static final int DEFAULT_PRIORITY = 10;

    /** The state every WireMock scenario starts in. */
    private static final String SCENARIO_START = "Started";

    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";
    private static final String PARAM_GRANT_TYPE = "grant_type";
    private static final String GRANT_REFRESH_TOKEN = "refresh_token";

    private static final long BOOT_TIMEOUT_SECONDS = 90L;
    private static final long POLL_INTERVAL_MILLIS = 250L;
    private static final Duration ADMIN_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient adminClient = HttpClient.newBuilder().connectTimeout(ADMIN_TIMEOUT).build();
    private final Map<Endpoint, Integer> scriptedAnswers = new EnumMap<>(Endpoint.class);
    private final KeyPair signingKey;
    private final String signingKeyId = UUID.randomUUID().toString();
    private final String proofKeyThumbprint;

    /** The compose echo container this rig connected to its network; empty until it is resolved. */
    private String echoContainer = "";

    /** The stub's published admin origin; empty until the stub is started. */
    private String adminOrigin = "";

    private StubIdentityProviderRig() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            signingKey = generator.generateKeyPair();
            proofKeyThumbprint = BffFapiControlsIT.publicKeyThumbprint(BffFapiControlsIT.signingKeyFile(PROOF_KEY_FILE));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot generate the stub identity provider's signing key", e);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the proof key " + PROOF_KEY_FILE, e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    /**
     * Starts the network, the stub and the gateway, and returns once the gateway reports ready.
     * Whatever a failed start had already created is removed before the failure propagates.
     *
     * @return the running rig; close it to remove everything it started
     */
    static StubIdentityProviderRig start() {
        StubIdentityProviderRig rig = new StubIdentityProviderRig();
        boolean started = false;
        try {
            rig.boot();
            started = true;
            return rig;
        } finally {
            if (!started) {
                rig.close();
            }
        }
    }

    private void boot() {
        String echo = ContainerHealthInspector.composeContainers().get(ECHO_SERVICE);
        assertNotNull(echo, () -> "the compose project runs no '" + ECHO_SERVICE + "' service");
        echoContainer = echo;
        // An aborted earlier run may have left its containers and its network behind.
        close();

        createNetwork(NETWORK);
        String keyStoreSecret = UUID.randomUUID().toString();
        writeKeyStore(keyStoreSecret);
        startAuxiliaryContainer(STUB, IMAGE,
                List.of("--network", NETWORK,
                        "--network-alias", IDENTITY_PROVIDER_ALIAS,
                        "-p", "127.0.0.1::" + STUB_ADMIN_PORT,
                        "-v", KEY_STORE_DIRECTORY.toAbsolutePath() + ":" + KEY_STORE_MOUNT + ":ro"),
                List.of("--port", String.valueOf(STUB_ADMIN_PORT),
                        "--https-port", String.valueOf(STUB_TLS_PORT),
                        "--https-keystore", KEY_STORE_MOUNT + "/" + KEY_STORE_FILE,
                        "--keystore-type", "PKCS12",
                        "--keystore-password", keyStoreSecret,
                        "--key-manager-password", keyStoreSecret,
                        "--disable-banner"));
        adminOrigin = "http://localhost:" + publishedPort(STUB, STUB_ADMIN_PORT);
        awaitStub();

        Map<?, ?> descriptor = writeDerivedDescriptor();
        registerDefaults(keySetPaths(descriptor));

        connectToNetwork(NETWORK, echo, ECHO_SERVICE);
        startBffGateway(
                new BffGateway(GATEWAY, NETWORK, GATEWAY, APPLICATION_PORT, DERIVED_DESCRIPTOR, "localhost.crt",
                        "localhost.key",
                        // The stub checks no credential; the descriptor only needs both references resolved.
                        List.of("OIDC_CLIENT_SECRET=stub-identity-provider-checks-no-secret",
                                "OIDC_DPOP_KEY_FILE=" + SIGNING_KEYS_MOUNT + "/" + PROOF_KEY_FILE)),
                List.of(new ReadOnlyMount(DOCKER.resolve("signing-keys"), SIGNING_KEYS_MOUNT)),
                List.of());
        awaitReadiness(GATEWAY, "https://localhost:" + publishedPort(GATEWAY, 9000),
                response -> response.statusCode() == 200, BOOT_TIMEOUT_SECONDS,
                "the gateway of the stub identity-provider rig to report readiness UP");
    }

    /**
     * Removes the gateway, the stub and the network, and disconnects the echo container. Every step is
     * attempted whatever the outcome of the one before, and none of them fails the caller: a teardown
     * failure must not mask the outcome of the test the rig served. Idempotent.
     */
    @Override
    public void close() {
        removeContainer(GATEWAY);
        removeContainer(STUB);
        if (!echoContainer.isEmpty()) {
            disconnectFromNetwork(NETWORK, echoContainer);
        }
        removeNetwork(NETWORK);
    }

    /**
     * @return the gateway's merged container output so far
     */
    String gatewayOutput() {
        return docker("read the log of " + GATEWAY, "logs", GATEWAY);
    }

    // ---------------------------------------------------------------------------------------------
    // Login
    // ---------------------------------------------------------------------------------------------

    /**
     * Completes a login on the rig's gateway for {@link #SUBJECT}.
     * <p>
     * The session is granted exactly the scope the gateway's pushed authorization request asked for, so
     * a login started on a scoped route yields a session whose active and granted scope sets are that
     * route's needed set.
     *
     * @param startPath           the gateway path the login starts on, a {@code require: session} route
     * @param accessTokenLifetime the lifetime of the access token the code exchange is answered with; a
     *                            value below the descriptor's {@code leeway_seconds} puts the session
     *                            inside the near-expiry window at once
     * @return the established gateway session; its Keycloak cookie jar is empty, since no Keycloak took
     *         part
     */
    Session login(String startPath, Duration accessTokenLifetime) {
        int pushedBefore = received(Endpoint.PUSHED_AUTHORIZATION_REQUEST).size();
        Response initiation = BffKeycloakLoginFlow.gateway(Map.of(), ORIGIN)
                .urlEncodingEnabled(false)
                .header("Accept", "text/html")
                .redirects().follow(false)
                .when().get(startPath)
                .then().extract().response();
        assertEquals(302, initiation.statusCode(), () -> "the navigation on " + startPath + " must start a login. "
                + OneOffGatewayContainers.gatewayLog(GATEWAY));
        String redirect = BffKeycloakLoginFlow.location(initiation);
        assertTrue(redirect.startsWith(AUTHORIZATION_ENDPOINT + "?") && redirect.contains("request_uri="),
                "the gateway must redirect to the stub's authorization endpoint with a request_uri");
        Map<String, String> gatewayCookies = new LinkedHashMap<>(initiation.getCookies());

        List<RecordedRequest> pushed = received(Endpoint.PUSHED_AUTHORIZATION_REQUEST);
        assertEquals(pushedBefore + 1, pushed.size(),
                "the login must have pushed exactly one authorization request to the stub");
        Map<String, String> request = pushed.getLast().form();
        String state = request.get("state");
        String nonce = request.get("nonce");
        String scope = request.get("scope");
        String redirectUri = request.get("redirect_uri");
        assertAll("the pushed authorization request the scripted login reads",
                () -> assertNotNull(state, "state"),
                () -> assertNotNull(nonce, "nonce"),
                () -> assertNotNull(scope, "scope"),
                () -> assertEquals(ORIGIN + "/auth/callback", redirectUri, "redirect_uri"));

        script(Endpoint.TOKEN, loginAnswer(scope, accessTokenLifetime, nonce));
        Response callback = BffKeycloakLoginFlow.gateway(gatewayCookies, ORIGIN)
                .urlEncodingEnabled(false)
                .header("Accept", "text/html")
                .redirects().follow(false)
                .when().get(redirectUri + "?code=" + UUID.randomUUID() + "&state="
                        + URLEncoder.encode(state, StandardCharsets.UTF_8))
                .then().extract().response();
        assertEquals(302, callback.statusCode(), () -> "the gateway must accept the stubbed login at its callback. "
                + OneOffGatewayContainers.gatewayLog(GATEWAY));
        gatewayCookies.putAll(callback.getCookies());
        BffKeycloakLoginFlow.assertCookiesFitBrowserBudget(callback);
        return new Session(gatewayCookies, callback.getDetailedCookies(), BffKeycloakLoginFlow.location(callback),
                Map.of());
    }

    // ---------------------------------------------------------------------------------------------
    // Scripting
    // ---------------------------------------------------------------------------------------------

    /**
     * A token-endpoint answer that grants {@code scopes}: a fresh access token carrying them as its
     * {@code scope} claim and bound to the gateway's proof key, a fresh refresh token, the token type
     * {@code DPoP}, and {@code scopes} again as the answer's own {@code scope} member — the member a
     * refresh reads to learn what it was granted. It carries no ID token.
     * <p>
     * Every call mints a new token, so two answers never relay the same bearer.
     *
     * @param scopes              the granted scopes, in the order they are rendered
     * @param accessTokenLifetime the lifetime of the access token, from now
     * @return the answer, to be {@linkplain #script(Endpoint, Answer) scripted} at {@link Endpoint#TOKEN}
     */
    Answer tokenAnswer(Collection<String> scopes, Duration accessTokenLifetime) {
        Instant now = Instant.now();
        return Answer.json(200, json(grant(String.join(" ", scopes), now, now.plus(accessTokenLifetime),
                UUID.randomUUID().toString())));
    }

    /**
     * The token answer of a code exchange: {@link #grant} together with an ID token that carries the
     * login's {@code nonce} and binds the granted access token through {@code at_hash}.
     */
    private Answer loginAnswer(String scope, Duration accessTokenLifetime, String nonce) {
        Instant now = Instant.now();
        Instant expiry = now.plus(accessTokenLifetime);
        String sessionId = UUID.randomUUID().toString();
        Map<String, Object> answer = grant(scope, now, expiry, sessionId);

        Map<String, Object> idClaims = commonClaims(now, expiry, sessionId);
        idClaims.put("aud", CLIENT_ID);
        idClaims.put("typ", "ID");
        idClaims.put("auth_time", now.getEpochSecond());
        idClaims.put("nonce", nonce);
        idClaims.put("at_hash", accessTokenHash(String.valueOf(answer.get("access_token"))));
        answer.put("id_token", sign(idClaims));
        return Answer.json(200, json(answer));
    }

    /** The members of a token answer that grants {@code scope}, without an ID token. */
    private Map<String, Object> grant(String scope, Instant now, Instant expiry, String sessionId) {
        Map<String, Object> accessClaims = commonClaims(now, expiry, sessionId);
        accessClaims.put("aud", "account");
        accessClaims.put("typ", "Bearer");
        accessClaims.put("scope", scope);
        accessClaims.put("cnf", Map.of("jkt", proofKeyThumbprint));

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("access_token", sign(accessClaims));
        answer.put("token_type", "DPoP");
        answer.put("expires_in", Duration.between(now, expiry).toSeconds());
        answer.put("refresh_token", "stub-refresh-token-" + UUID.randomUUID());
        answer.put("scope", scope);
        return answer;
    }

    /**
     * Appends one answer to an endpoint's script. Each scripted answer is served to one request, in the
     * order the answers were added; once the script is used up the endpoint answers with its default
     * again. See the class documentation for the limit under concurrent requests.
     *
     * @param endpoint the endpoint
     * @param answer   the answer the next unanswered request to the endpoint receives
     */
    synchronized void script(Endpoint endpoint, Answer answer) {
        Objects.requireNonNull(answer, "answer");
        int position = scriptedAnswers.merge(endpoint, 1, Integer::sum);
        Map<String, Object> stub = stub(endpoint.method, endpoint.path, answer, SCRIPTED_PRIORITY);
        stub.put("scenarioName", endpoint.name());
        stub.put("requiredScenarioState", scenarioState(position - 1));
        stub.put("newScenarioState", scenarioState(position));
        register(stub);
    }

    private static String scenarioState(int position) {
        return position == 0 ? SCENARIO_START : "answered-" + position;
    }

    // ---------------------------------------------------------------------------------------------
    // Journal
    // ---------------------------------------------------------------------------------------------

    /**
     * @param endpoint the endpoint
     * @return every request the stub has recorded at the endpoint so far, in arrival order — including
     *         a request it answered with an error or a connection reset
     */
    List<RecordedRequest> received(Endpoint endpoint) {
        String found = admin("POST", "/__admin/requests/find",
                json(Map.of("method", endpoint.method, "urlPath", endpoint.path)), 200);
        List<Map<String, Object>> requests = new JsonPath(found).getList("requests");
        assertNotNull(requests, () -> "the stub's journal answered no request list: " + found);
        return requests.stream()
                .map(RecordedRequest::of)
                .sorted(Comparator.comparingLong(RecordedRequest::loggedAtMillis))
                .toList();
    }

    /**
     * @return the refresh grants among the requests recorded at {@link Endpoint#TOKEN}, in arrival order
     */
    List<RecordedRequest> refreshGrants() {
        return received(Endpoint.TOKEN).stream()
                .filter(request -> GRANT_REFRESH_TOKEN.equals(request.form().get(PARAM_GRANT_TYPE)))
                .toList();
    }

    // ---------------------------------------------------------------------------------------------
    // Stub registration
    // ---------------------------------------------------------------------------------------------

    private void registerDefaults(Set<String> keySetPaths) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuer", ISSUER);
        metadata.put("authorization_endpoint", AUTHORIZATION_ENDPOINT);
        metadata.put("token_endpoint", IDENTITY_PROVIDER_ORIGIN + Endpoint.TOKEN.path);
        metadata.put("pushed_authorization_request_endpoint",
                IDENTITY_PROVIDER_ORIGIN + Endpoint.PUSHED_AUTHORIZATION_REQUEST.path);
        metadata.put("revocation_endpoint", IDENTITY_PROVIDER_ORIGIN + Endpoint.REVOCATION.path);
        metadata.put("jwks_uri", ISSUER + "/protocol/openid-connect/certs");
        metadata.put("code_challenge_methods_supported", List.of("S256"));
        metadata.put("dpop_signing_alg_values_supported", List.of("PS256", "ES256"));
        registerDefault(Endpoint.DISCOVERY, Answer.json(200, json(metadata)));
        registerDefault(Endpoint.PUSHED_AUTHORIZATION_REQUEST, Answer.json(201, json(Map.of(
                "request_uri", "urn:ietf:params:oauth:request_uri:stub-identity-provider",
                "expires_in", 60))));
        registerDefault(Endpoint.TOKEN, Answer.json(400, json(Map.of("error", "invalid_grant"))));
        registerDefault(Endpoint.REVOCATION, Answer.of(200));

        RSAPublicKey publicKey = (RSAPublicKey) signingKey.getPublic();
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("kty", "RSA");
        key.put("kid", signingKeyId);
        key.put("use", "sig");
        key.put("alg", "RS256");
        key.put("n", base64Url(unsigned(publicKey.getModulus())));
        key.put("e", base64Url(unsigned(publicKey.getPublicExponent())));
        Answer keySet = Answer.json(200, json(Map.of("keys", List.of(key))));
        for (String path : keySetPaths) {
            register(stub("GET", path, keySet, DEFAULT_PRIORITY));
        }
    }

    private void registerDefault(Endpoint endpoint, Answer answer) {
        register(stub(endpoint.method, endpoint.path, answer, DEFAULT_PRIORITY));
    }

    private void register(Map<String, Object> stub) {
        admin("POST", "/__admin/mappings", json(stub), 201);
    }

    private static Map<String, Object> stub(String method, String path, Answer answer, int priority) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (answer.connectionReset()) {
            response.put("fault", "CONNECTION_RESET_BY_PEER");
        } else {
            response.put("status", answer.status());
            response.put("headers", answer.headers());
            if (!answer.body().isEmpty()) {
                response.put("body", answer.body());
            }
        }
        if (answer.delay().isPositive()) {
            response.put("fixedDelayMilliseconds", answer.delay().toMillis());
        }
        Map<String, Object> stub = new LinkedHashMap<>();
        stub.put("priority", priority);
        stub.put("request", Map.of("method", method, "urlPath", path));
        stub.put("response", response);
        return stub;
    }

    private String admin(String method, String path, String body, int expectedStatus) {
        HttpResponse<String> response;
        try {
            response = sendAdmin(method, path, body);
        } catch (IOException e) {
            throw new UncheckedIOException("the stub's admin API did not answer " + method + " " + path, e);
        }
        assertEquals(expectedStatus, response.statusCode(),
                () -> "the stub's admin API refused " + method + " " + path + ": " + response.body());
        return response.body();
    }

    private HttpResponse<String> sendAdmin(String method, String path, String body) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(adminOrigin + path))
                .timeout(ADMIN_TIMEOUT)
                .header(CONTENT_TYPE, APPLICATION_JSON)
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            return adminClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling the stub's admin API", e);
        }
    }

    @SuppressWarnings("java:S2925") // NOSONAR java:S2925 - bounded poll of an external container's state
    private void awaitStub() {
        long deadline = System.nanoTime() + Duration.ofSeconds(BOOT_TIMEOUT_SECONDS).toNanos();
        String lastObservation = "no answer yet";
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> response = sendAdmin("GET", "/__admin/mappings", "");
                if (response.statusCode() == 200) {
                    return;
                }
                lastObservation = "status " + response.statusCode();
            } catch (IOException notAnsweringYet) {
                lastObservation = notAnsweringYet.toString();
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the stub identity provider", interrupted);
            }
        }
        fail("timed out after " + BOOT_TIMEOUT_SECONDS + "s waiting for the stub identity provider's admin API. "
                + "Last observation: " + lastObservation + " Stub log:\n" + dockerQuietly("logs", STUB));
    }

    // ---------------------------------------------------------------------------------------------
    // Derived files
    // ---------------------------------------------------------------------------------------------

    /**
     * Writes the gateway descriptor: the committed refresh descriptor with its origin retargeted at
     * {@link #ORIGIN}. The result is asserted to name the rig's origin in every key that names one and
     * the stub as its issuer, so a change to the committed descriptor fails here instead of producing a
     * gateway that differs from it in more than the stated way.
     *
     * @return the parsed derived descriptor
     */
    private static Map<?, ?> writeDerivedDescriptor() {
        Map<?, ?> descriptor;
        try {
            String source = Files.readString(SOURCE_DESCRIPTOR);
            assertTrue(source.contains(SOURCE_ORIGIN),
                    () -> SOURCE_DESCRIPTOR + " must name the origin " + SOURCE_ORIGIN + " the derivation retargets");
            Files.createDirectories(WORK_DIRECTORY);
            Files.writeString(DERIVED_DESCRIPTOR, source.replace(SOURCE_ORIGIN, ORIGIN));
            Files.setPosixFilePermissions(DERIVED_DESCRIPTOR, PosixFilePermissions.fromString(FILE_MODE));
            try (InputStream in = Files.newInputStream(DERIVED_DESCRIPTOR)) {
                descriptor = mapping(new Yaml().load(in), DERIVED_DESCRIPTOR.toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot derive the gateway descriptor from " + SOURCE_DESCRIPTOR, e);
        }
        Map<?, ?> oidc = mapping(descriptor.get("oidc"), "oidc");
        Map<?, ?> csrf = mapping(mapping(oidc.get("session"), "oidc.session").get("csrf"), "oidc.session.csrf");
        assertAll("the derived descriptor " + DERIVED_DESCRIPTOR,
                () -> assertEquals(ISSUER, oidc.get("issuer"), "oidc.issuer must be the stub"),
                () -> assertEquals(CLIENT_ID, oidc.get("client_id"), "oidc.client_id"),
                () -> assertEquals(ORIGIN + "/auth/callback", oidc.get("redirect_uri"), "oidc.redirect_uri"),
                () -> assertTrue(String.valueOf(mapping(oidc.get("logout"), "oidc.logout")
                                .get("post_logout_redirect_uri")).startsWith(ORIGIN + "/"),
                        "oidc.logout.post_logout_redirect_uri must name the rig's origin"),
                () -> assertEquals(List.of(ORIGIN), csrf.get("trusted_origins"), "oidc.session.csrf.trusted_origins"));
        return descriptor;
    }

    /**
     * The path of every {@code jwks.url} the descriptor names. Each must dial the stub: a key set the
     * gateway cannot load keeps its readiness {@code DOWN}.
     */
    private static Set<String> keySetPaths(Map<?, ?> descriptor) {
        Object issuers = mapping(descriptor.get("token_validation"), "token_validation").get("issuers");
        Set<String> paths = new LinkedHashSet<>();
        for (Object issuer : assertInstanceOf(List.class, issuers, "token_validation.issuers must be a list")) {
            Map<?, ?> jwks = mapping(mapping(issuer, "a token_validation issuer").get("jwks"), "jwks");
            if ("http".equals(jwks.get("source"))) {
                URI url = URI.create(String.valueOf(jwks.get("url")));
                assertEquals(IDENTITY_PROVIDER_ALIAS + ":" + STUB_TLS_PORT, url.getAuthority(),
                        () -> "every jwks.url of " + SOURCE_DESCRIPTOR + " must dial the stub, found " + url);
                paths.add(url.getPath());
            }
        }
        assertTrue(paths.contains(REALM_PATH + "/protocol/openid-connect/certs"),
                () -> "the descriptor must fetch the key set of " + ISSUER + ", found " + paths);
        return paths;
    }

    private static Map<?, ?> mapping(Object node, String what) {
        return assertInstanceOf(Map.class, node, () -> what + " must be a mapping");
    }

    /**
     * Packs the committed stack certificate and its private key into the PKCS12 key store the stub
     * serves TLS from. The store's secret is generated per run and handed to the stub on its command
     * line; it protects nothing, since the key it wraps is committed test material.
     */
    private static void writeKeyStore(String secret) {
        Path keyStore = KEY_STORE_DIRECTORY.resolve(KEY_STORE_FILE);
        try {
            Certificate[] chain;
            try (InputStream in = Files.newInputStream(CERTIFICATES.resolve("localhost.crt"))) {
                chain = CertificateFactory.getInstance("X.509").generateCertificates(in).toArray(Certificate[]::new);
            }
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry("stub-identity-provider", stackPrivateKey(), secret.toCharArray(), chain);
            Files.createDirectories(KEY_STORE_DIRECTORY);
            Files.setPosixFilePermissions(KEY_STORE_DIRECTORY, PosixFilePermissions.fromString(DIRECTORY_MODE));
            try (OutputStream out = Files.newOutputStream(keyStore)) {
                store.store(out, secret.toCharArray());
            }
            Files.setPosixFilePermissions(keyStore, PosixFilePermissions.fromString(FILE_MODE));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the stub's key store " + keyStore, e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot build the stub's key store from the stack certificate", e);
        }
    }

    private static PrivateKey stackPrivateKey() throws IOException, GeneralSecurityException {
        String begin = "-----BEGIN PRIVATE KEY-----";
        String end = "-----END PRIVATE KEY-----";
        String pem = Files.readString(CERTIFICATES.resolve("localhost.key"));
        int from = pem.indexOf(begin);
        int to = pem.indexOf(end);
        assertTrue(from >= 0 && to > from, "localhost.key must hold an unencrypted PKCS#8 PRIVATE KEY block");
        byte[] encoded = Base64.getMimeDecoder().decode(pem.substring(from + begin.length(), to));
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    // ---------------------------------------------------------------------------------------------
    // Tokens
    // ---------------------------------------------------------------------------------------------

    private static Map<String, Object> commonClaims(Instant issuedAt, Instant expiry, String sessionId) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("sub", SUBJECT);
        claims.put("azp", CLIENT_ID);
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("exp", expiry.getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("sid", sessionId);
        return claims;
    }

    private String sign(Map<String, Object> claims) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", signingKeyId);
        String signingInput = base64Url(json(header).getBytes(StandardCharsets.UTF_8)) + "."
                + base64Url(json(claims).getBytes(StandardCharsets.UTF_8));
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(signingKey.getPrivate());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + base64Url(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot sign a stub token", e);
        }
    }

    /** The OIDC Core {@code at_hash} of an access token under an {@code RS256} ID token. */
    private static String accessTokenHash(String accessToken) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(accessToken.getBytes(StandardCharsets.US_ASCII));
            return base64Url(Arrays.copyOf(hash, hash.length / 2));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("no SHA-256", e);
        }
    }

    /** The big-endian magnitude of a non-negative integer without a sign byte, as RFC 7518 prescribes. */
    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        return bytes.length > 1 && bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ---------------------------------------------------------------------------------------------
    // JSON
    // ---------------------------------------------------------------------------------------------

    /** Renders maps, collections, numbers, booleans and strings as JSON; anything else as a string. */
    private static String json(Object value) {
        return switch (value) {
            case Map<?, ?> members -> members.entrySet().stream()
                    .map(member -> quoted(String.valueOf(member.getKey())) + ":" + json(member.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
            case Collection<?> elements -> elements.stream()
                    .map(StubIdentityProviderRig::json)
                    .collect(Collectors.joining(",", "[", "]"));
            case Number number -> number.toString();
            case Boolean flag -> flag.toString();
            default -> quoted(String.valueOf(value));
        };
    }

    private static String quoted(String text) {
        StringBuilder json = new StringBuilder("\"");
        for (char character : text.toCharArray()) {
            if (character == '"' || character == '\\') {
                json.append('\\').append(character);
            } else if (character < 0x20) {
                json.append("\\u%04x".formatted((int) character));
            } else {
                json.append(character);
            }
        }
        return json.append('"').toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Types
    // ---------------------------------------------------------------------------------------------

    /**
     * One endpoint of the stub that a test may script and read the journal of: the method it is served
     * on and its path, which is the one the compose Keycloak serves it at.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    enum Endpoint {

        /** The provider metadata document, below the issuer. */
        DISCOVERY("GET", REALM_PATH + "/.well-known/openid-configuration"),

        /**
         * The RFC 9126 pushed-authorization-request endpoint. It accepts every push with {@code 201}, a
         * {@code request_uri} and an {@code expires_in}.
         */
        PUSHED_AUTHORIZATION_REQUEST("POST", REALM_PATH + "/protocol/openid-connect/ext/par/request"),

        /** The token endpoint. It refuses every grant with {@code 400} and {@code invalid_grant}. */
        TOKEN("POST", REALM_PATH + "/protocol/openid-connect/token"),

        /** The RFC 7009 revocation endpoint. It answers {@code 200} with no body. */
        REVOCATION("POST", REALM_PATH + "/protocol/openid-connect/revoke");

        private final String method;
        private final String path;

        Endpoint(String method, String path) {
            this.method = method;
            this.path = path;
        }
    }

    /**
     * One answer of an endpoint.
     *
     * @param status          the HTTP status; not used by a connection reset
     * @param headers         the response headers, empty when none
     * @param body            the response body, empty for none
     * @param delay           how long the stub waits before it answers; zero for no delay
     * @param connectionReset whether the stub resets the connection instead of answering
     * @author API Sheriff Team
     * @since 1.0
     */
    record Answer(int status, Map<String, String> headers, String body, Duration delay, boolean connectionReset) {

        /**
         * Canonical constructor defensively copying the headers.
         */
        Answer {
            headers = Map.copyOf(headers);
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(delay, "delay");
        }

        /**
         * @param status the HTTP status
         * @return an answer with that status, no header and no body
         */
        static Answer of(int status) {
            return new Answer(status, Map.of(), "", Duration.ZERO, false);
        }

        /**
         * @param status the HTTP status
         * @param body   the JSON body
         * @return an answer with that status and body, declared as {@code application/json}
         */
        static Answer json(int status, String body) {
            return new Answer(status, Map.of(CONTENT_TYPE, APPLICATION_JSON), body, Duration.ZERO, false);
        }

        /**
         * @return an answer that resets the connection once the request has been read, so the caller
         *         sees a transport failure and no HTTP status
         */
        static Answer reset() {
            return new Answer(0, Map.of(), "", Duration.ZERO, true);
        }

        /**
         * @param pause how long the stub waits before it answers
         * @return this answer, delayed
         */
        Answer delayedBy(Duration pause) {
            return new Answer(status, headers, body, pause, connectionReset);
        }
    }

    /**
     * One request the stub recorded.
     *
     * @param method         the HTTP method
     * @param headers        the request headers, keyed by lower-cased name; a header sent several times
     *                       is rendered as WireMock's journal renders it
     * @param body           the request body, empty when the request carried none
     * @param loggedAtMillis when the stub recorded the request, in epoch milliseconds
     * @author API Sheriff Team
     * @since 1.0
     */
    record RecordedRequest(String method, Map<String, String> headers, String body, long loggedAtMillis) {

        /**
         * Canonical constructor defensively copying the headers.
         */
        RecordedRequest {
            headers = Map.copyOf(headers);
        }

        private static RecordedRequest of(Map<String, Object> logged) {
            Map<String, String> headers = new LinkedHashMap<>();
            if (logged.get("headers") instanceof Map<?, ?> sent) {
                sent.forEach((name, value) ->
                        headers.put(String.valueOf(name).toLowerCase(Locale.ROOT), String.valueOf(value)));
            }
            Object body = logged.get("body");
            Number loggedDate = assertInstanceOf(Number.class, logged.get("loggedDate"),
                    "a journal entry carries its loggedDate");
            return new RecordedRequest(String.valueOf(logged.get("method")), headers,
                    body == null ? "" : body.toString(), loggedDate.longValue());
        }

        /**
         * @param name the header name, in any letter case
         * @return the value of that header, empty when the request did not carry it
         */
        Optional<String> header(String name) {
            return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
        }

        /**
         * Decodes the body as {@code application/x-www-form-urlencoded}.
         *
         * @return the form parameters by name, in body order; empty for an empty body
         */
        Map<String, String> form() {
            Map<String, String> parameters = new LinkedHashMap<>();
            if (body.isEmpty()) {
                return parameters;
            }
            for (String pair : body.split("&")) {
                String[] nameValue = pair.split("=", 2);
                parameters.put(URLDecoder.decode(nameValue[0], StandardCharsets.UTF_8),
                        nameValue.length == 2 ? URLDecoder.decode(nameValue[1], StandardCharsets.UTF_8) : "");
            }
            return parameters;
        }
    }
}
