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

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.Headers;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import okio.ByteString;
import org.jspecify.annotations.Nullable;

/**
 * An identity-provider fixture the unit tests drive a produced BFF runtime against: a local
 * server that serves an OIDC discovery document and the back-channel endpoints that document names,
 * records every request it receives, and answers each endpoint from a script a test may fill.
 *
 * <h2>Why it serves TLS</h2>
 *
 * {@code BffRuntimeProducer.backChannelConfiguration} sets the token engine's
 * {@code allowInsecureHttp} only under the explicit opt-in {@code oidc.back_channel_allow_insecure_http},
 * which boot validation admits only together with an {@code http} {@code oidc.back_channel_origin}.
 * Without it a produced runtime cannot dial plain HTTP on any leg: {@code DiscoveryResolver} refuses
 * a non-{@code https} issuer before it sends anything, and the token, revocation and pushed-request
 * clients build their request through a handler builder that refuses an {@code http} URI. A fixture
 * that is to be reached by a <em>produced</em> runtime on its default posture therefore has to speak
 * TLS.
 *
 * <h2>How a runtime reaches it</h2>
 *
 * Through configuration alone, the route a deployment with a private-CA identity provider takes:
 * {@code oidc.issuer} is {@link #issuer()}, {@code egress_tls.oidc_tls_profile} names a profile the
 * test registry binds to {@link #rootCertificate()}, and {@code egress_tls.oidc_verify_hostname}
 * stays at its default. On start the fixture issues a throwaway root and a leaf signed by it whose
 * subject alternative name is {@link LoopbackHost#ADDRESS}, so hostname verification stays on and
 * is satisfied. No certificate, key or trust store is committed, no system property is set and the
 * JVM default trust store is not touched.
 *
 * <h2>Advertising a public issuer</h2>
 *
 * {@link #start()} advertises the address it listens on, so the issuer is reachable as declared.
 * {@link #startAdvertising(String)} instead declares a given public issuer — an origin, optionally
 * followed by a path — as the discovery document's {@code issuer}, and names every endpoint beneath
 * it, while the listener stays on loopback and serves each endpoint beneath that issuer's path. A
 * test can then name an issuer nothing resolves and make {@code oidc.back_channel_origin} set to
 * {@link #origin()} the only path to the fixture: discovery and every back-channel call reach it only
 * if the engine re-targets them, and the authorization endpoint the browser is sent to stays on the
 * public issuer.
 *
 * <h2>What it serves</h2>
 *
 * Each {@link Endpoint} is one entry: its path, its HTTP method, its default answer and the member
 * of the discovery document that names it. A request matching no entry by path and method is
 * answered {@code 404}. A matching request is recorded under its endpoint with its method, headers
 * and body, and is then answered from the endpoint's script when a test has
 * {@linkplain #script(Endpoint, Answer) filled} it, and otherwise from the endpoint's default.
 * <p>
 * The fixture mints no token and validates one thing: a DPoP proof's {@code htu} and {@code htm}
 * against the request it arrived on, as Keycloak does. A recorded request carrying a {@code DPoP}
 * header is answered {@code 400} {@code invalid_dpop_proof} — without consuming the endpoint's
 * script — unless the proof's {@code htu} equals the URL the request was received on, built as
 * {@link #origin()} followed by the request's encoded path with no query and no fragment (RFC 9449
 * section 4.3), and its {@code htm} equals the request method. A header whose payload segment does
 * not decode to a JSON object is refused the same way. The check applies to every endpoint, because
 * a proof is judged wherever one arrives; a request without a {@code DPoP} header is unaffected. The
 * proof's signature is deliberately not verified: the fixture judges the binding to the request,
 * not the key. Without this check the stub suites stayed green while Keycloak refused every proof of
 * a gateway that signed the advertised URL instead of the one it dialled.
 * <p>
 * The authority of the comparand comes from {@link #origin()} and never from the URL the server
 * library reconstructs for a request, whose host spelling may follow the {@code Host} header or a
 * lookup of the local socket address rather than the URL the client dialled.
 * <p>
 * The token endpoint refuses every grant by default, because an assertion on the shape of a request
 * needs the request and not a grant. The pushed-authorization-request endpoint accepts every push by
 * default, because a login has to get past it before the runtime sends anything else.
 *
 * <h2>Binding</h2>
 *
 * The listener binds {@link LoopbackHost#ADDRESS} on an ephemeral port, never the wildcard.
 * <p>
 * Hand-written rather than driven by a mocking framework, per this project's testing policy. Not
 * named {@code *Test} and not prefixed {@code Test}, so Surefire does not run it as a test class.
 * <p>
 * Thread-safe: the recordings and the scripts are concurrent collections, so a test may read a
 * recording while the server thread serves a request.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class StubIdentityProvider implements AutoCloseable {

    private static final String AUTHORIZATION_PATH = "/authorize";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";
    private static final int NOT_FOUND = 404;
    private static final String DPOP_HEADER = "DPoP";
    private static final String HTU_MISMATCH = "DPoP HTTP URL mismatch";
    private static final String HTM_MISMATCH = "DPoP HTTP method mismatch";
    private static final ObjectMapper PROOF_READER = new ObjectMapper();
    private static final Base64.Decoder BASE64_URL = Base64.getUrlDecoder();

    private final MockWebServer server;
    private final X509Certificate rootCertificate;
    /** The advertised issuer, {@code null} when the fixture advertises the address it listens on. */
    private final @Nullable String advertisedIssuer;
    /** The path every endpoint is served beneath — the advertised issuer's path, empty for none. */
    private final String servedPathPrefix;
    private final Map<Endpoint, List<ReceivedRequest>> received = new EnumMap<>(Endpoint.class);
    private final Map<Endpoint, Queue<Answer>> scripts = new EnumMap<>(Endpoint.class);

    private StubIdentityProvider(MockWebServer server, X509Certificate rootCertificate,
            @Nullable String advertisedIssuer) {
        this.server = server;
        this.rootCertificate = rootCertificate;
        this.advertisedIssuer = advertisedIssuer;
        String issuerPath = advertisedIssuer == null ? null : URI.create(advertisedIssuer).getRawPath();
        this.servedPathPrefix = issuerPath == null ? "" : issuerPath;
        for (Endpoint endpoint : Endpoint.values()) {
            received.put(endpoint, new CopyOnWriteArrayList<>());
            scripts.put(endpoint, new ConcurrentLinkedQueue<>());
        }
    }

    /**
     * Issues the certificate chain and starts the listener, advertising the address it listens on:
     * {@link #issuer()} and {@link #origin()} are the same loopback origin.
     *
     * @return the running fixture; close it to stop the listener
     * @throws IOException when the listener cannot be bound
     */
    public static StubIdentityProvider start() throws IOException {
        return startListener(null);
    }

    /**
     * Issues the certificate chain and starts the listener on loopback, advertising {@code issuer}
     * instead of the address it listens on: the discovery document declares {@code issuer} and names
     * every endpoint beneath it, and each endpoint is served beneath {@code issuer}'s path. The fixture
     * is then reachable only through {@link #origin()}, which is what a test names as
     * {@code oidc.back_channel_origin}.
     *
     * @param issuer the public issuer to advertise: an absolute {@code https} URI, an origin optionally
     *               followed by a path and with no trailing {@code /}
     * @return the running fixture; close it to stop the listener
     * @throws IOException when the listener cannot be bound
     */
    public static StubIdentityProvider startAdvertising(String issuer) throws IOException {
        URI parsed = URI.create(Objects.requireNonNull(issuer, "issuer"));
        if (!"https".equals(parsed.getScheme()) || parsed.getHost() == null || issuer.endsWith("/")
                || parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException("an advertised issuer is an https origin, optionally followed by "
                    + "a path, with no trailing '/', no query and no fragment: " + issuer);
        }
        return startListener(issuer);
    }

    private static StubIdentityProvider startListener(@Nullable String advertisedIssuer) throws IOException {
        HeldCertificate root = new HeldCertificate.Builder()
                .certificateAuthority(0)
                .commonName("API Sheriff stub identity provider root")
                .duration(1, TimeUnit.HOURS)
                .build();
        // The leaf names the dialled address. okhttp emits an iPAddress subject alternative name for a
        // parsable address, which is the form an IP-literal dial is matched against.
        HeldCertificate leaf = new HeldCertificate.Builder()
                .signedBy(root)
                .commonName(LoopbackHost.ADDRESS)
                .addSubjectAlternativeName(LoopbackHost.ADDRESS)
                .duration(1, TimeUnit.HOURS)
                .build();
        MockWebServer server = new MockWebServer();
        server.useHttps(new HandshakeCertificates.Builder()
                .heldCertificate(leaf, root.certificate())
                .build()
                .sslSocketFactory());
        StubIdentityProvider provider = new StubIdentityProvider(server, root.certificate(), advertisedIssuer);
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return provider.serve(request);
            }
        });
        server.start(InetAddress.getByName(LoopbackHost.ADDRESS), 0);
        return provider;
    }

    /**
     * @return the issuer the discovery document declares, and so the one an {@code oidc.issuer} must
     *         name: the advertised issuer of {@link #startAdvertising(String)}, otherwise
     *         {@link #origin()}
     */
    public String issuer() {
        return advertisedIssuer == null ? origin() : advertisedIssuer;
    }

    /**
     * @return the origin the listener is reachable at: {@code https}, the loopback address and the
     *         bound port. For a fixture started by {@link #startAdvertising(String)} it is the origin an
     *         {@code oidc.back_channel_origin} must name to reach it
     */
    public String origin() {
        return "https://" + LoopbackHost.ADDRESS + ":" + server.getPort();
    }

    /**
     * @return the root certificate the served leaf chains to — the one anchor a trust profile has to
     *         hold for a client to reach this fixture
     */
    public X509Certificate rootCertificate() {
        return rootCertificate;
    }

    /**
     * @param endpoint the endpoint
     * @return the absolute URL the discovery document names for the endpoint, beneath
     *         {@link #issuer()} — for a fixture started by {@link #startAdvertising(String)} an address on
     *         the public issuer, not on {@link #origin()}
     */
    public String url(Endpoint endpoint) {
        return issuer() + endpoint.path;
    }

    /**
     * @param endpoint the endpoint
     * @return every request the endpoint has received so far, in arrival order
     */
    public List<ReceivedRequest> received(Endpoint endpoint) {
        return List.copyOf(received.get(endpoint));
    }

    /**
     * Appends one answer to an endpoint's script. Each scripted answer is served to exactly one
     * request, in the order the answers were added; once the script is empty the endpoint answers
     * with its default again.
     *
     * @param endpoint the endpoint
     * @param answer   the answer the next unanswered request to the endpoint receives
     */
    public void script(Endpoint endpoint, Answer answer) {
        scripts.get(endpoint).add(Objects.requireNonNull(answer, "answer"));
    }

    @Override
    public void close() {
        server.close();
    }

    private MockResponse serve(RecordedRequest request) {
        String path = request.getUrl().encodedPath();
        for (Endpoint endpoint : Endpoint.values()) {
            if ((servedPathPrefix + endpoint.path).equals(path) && endpoint.method.equals(request.getMethod())) {
                received.get(endpoint).add(ReceivedRequest.of(request));
                Optional<Answer> refusal = dpopRefusal(request.getHeaders().get(DPOP_HEADER), origin() + path,
                        request.getMethod());
                if (refusal.isPresent()) {
                    return render(refusal.get());
                }
                Answer scripted = scripts.get(endpoint).poll();
                return render(scripted == null ? endpoint.defaultAnswer.apply(issuer()) : scripted);
            }
        }
        return new MockResponse.Builder().code(NOT_FOUND).build();
    }

    /**
     * Judges a DPoP proof's binding to the request it arrived on, as Keycloak does: its {@code htu}
     * must equal the received URL and its {@code htm} the request method. The signature is not
     * verified.
     *
     * @param proof       the {@code DPoP} header value, {@code null} when the request carried none
     * @param receivedUrl the URL the request was received on: {@link #origin()} followed by the
     *                    encoded path, with no query and no fragment
     * @param method      the request method
     * @return the refusal to answer with, empty when the request carries no proof or the proof matches
     */
    private static Optional<Answer> dpopRefusal(@Nullable String proof, String receivedUrl, String method) {
        if (proof == null) {
            return Optional.empty();
        }
        Optional<JsonNode> claims = proofClaims(proof);
        if (claims.isEmpty() || !receivedUrl.equals(claims.get().path("htu").asText(null))) {
            return Optional.of(invalidDpopProof(HTU_MISMATCH));
        }
        if (!method.equals(claims.get().path("htm").asText(null))) {
            return Optional.of(invalidDpopProof(HTM_MISMATCH));
        }
        return Optional.empty();
    }

    /**
     * @param proof a {@code DPoP} header value
     * @return the proof's payload claims, empty when the value is no three-segment compact JWS whose
     *         payload segment decodes to a JSON object
     */
    private static Optional<JsonNode> proofClaims(String proof) {
        String[] segments = proof.split("\\.", -1);
        if (segments.length != 3) {
            return Optional.empty();
        }
        try {
            JsonNode claims = PROOF_READER.readTree(BASE64_URL.decode(segments[1]));
            return claims != null && claims.isObject() ? Optional.of(claims) : Optional.empty();
        } catch (IOException | IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    private static Answer invalidDpopProof(String description) {
        return Answer.json(400, "{\"error\":\"invalid_dpop_proof\",\"error_description\":\"%s\"}"
                .formatted(description));
    }

    private static MockResponse render(Answer answer) {
        MockResponse.Builder response = new MockResponse.Builder().code(answer.status());
        answer.headers().forEach(response::addHeader);
        if (!answer.body().isEmpty()) {
            response.body(answer.body());
        }
        return response.build();
    }

    /**
     * Renders the discovery document for an issuer: the issuer itself, an authorization endpoint,
     * {@code S256} among the code-challenge methods, and one member per {@link Endpoint} that
     * declares one.
     */
    private static Answer discoveryDocument(String issuer) {
        String endpointMembers = Stream.of(Endpoint.values())
                .filter(endpoint -> endpoint.discoveryMember != null)
                .map(endpoint -> "\"%s\":\"%s%s\"".formatted(endpoint.discoveryMember, issuer, endpoint.path))
                .collect(Collectors.joining(","));
        return Answer.json(200, """
                {"issuer":"%s","authorization_endpoint":"%s%s",%s,"code_challenge_methods_supported":["S256"]}"""
                .formatted(issuer, issuer, AUTHORIZATION_PATH, endpointMembers));
    }

    /**
     * One served endpoint: its path beneath the issuer, the method it is served on, its default
     * answer and the member of the discovery document that names it.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    public enum Endpoint {

        /** The OIDC discovery document. It names every other endpoint and is named by none. */
        DISCOVERY("/.well-known/openid-configuration", "GET", null, StubIdentityProvider::discoveryDocument),

        /** The token endpoint. It refuses every grant with {@code 400} and {@code invalid_grant}. */
        TOKEN("/token", "POST", "token_endpoint", _ -> Answer.json(400, "{\"error\":\"invalid_grant\"}")),

        /** The RFC 7009 revocation endpoint. It answers {@code 200} with no body. */
        REVOCATION("/revoke", "POST", "revocation_endpoint", _ -> Answer.of(200)),

        /**
         * The RFC 9126 pushed-authorization-request endpoint. It answers {@code 201} with a
         * {@code request_uri} and an {@code expires_in}.
         */
        PUSHED_AUTHORIZATION_REQUEST("/par", "POST", "pushed_authorization_request_endpoint",
                _ -> Answer.json(201,
                        "{\"request_uri\":\"urn:ietf:params:oauth:request_uri:stub-identity-provider\",\"expires_in\":60}"));

        private final String path;
        private final String method;
        private final @Nullable String discoveryMember;
        private final Function<String, Answer> defaultAnswer;

        Endpoint(String path, String method, @Nullable String discoveryMember,
                Function<String, Answer> defaultAnswer) {
            this.path = path;
            this.method = method;
            this.discoveryMember = discoveryMember;
            this.defaultAnswer = defaultAnswer;
        }
    }

    /**
     * One answer of an endpoint.
     *
     * @param status  the HTTP status
     * @param headers the response headers, empty when none
     * @param body    the response body, empty for none
     * @author API Sheriff Team
     * @since 1.0
     */
    public record Answer(int status, Map<String, String> headers, String body) {

        /**
         * Canonical constructor defensively copying the headers.
         */
        public Answer {
            headers = Map.copyOf(headers);
            Objects.requireNonNull(body, "body");
        }

        /**
         * @param status the HTTP status
         * @return an answer with that status, no header and no body
         */
        public static Answer of(int status) {
            return new Answer(status, Map.of(), "");
        }

        /**
         * @param status the HTTP status
         * @param body   the JSON body
         * @return an answer with that status and body, declared as {@code application/json}
         */
        public static Answer json(int status, String body) {
            return new Answer(status, Map.of(CONTENT_TYPE, APPLICATION_JSON), body);
        }
    }

    /**
     * One request an endpoint received.
     *
     * @param method  the HTTP method
     * @param headers the request headers, keyed by lower-cased name, each with its values in order
     * @param body    the request body, empty when the request carried none
     * @author API Sheriff Team
     * @since 1.0
     */
    public record ReceivedRequest(String method, Map<String, List<String>> headers, String body) {

        /**
         * Canonical constructor defensively copying the headers.
         */
        public ReceivedRequest {
            headers = Map.copyOf(headers);
        }

        private static ReceivedRequest of(RecordedRequest request) {
            Headers sent = request.getHeaders();
            Map<String, List<String>> headers = new LinkedHashMap<>();
            for (String name : sent.names()) {
                headers.put(name.toLowerCase(Locale.ROOT), List.copyOf(sent.values(name)));
            }
            ByteString body = request.getBody();
            return new ReceivedRequest(request.getMethod(), headers, body == null ? "" : body.utf8());
        }

        /**
         * @param name the header name, in any letter case
         * @return the first value of that header, empty when the request did not carry it
         */
        public Optional<String> header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
        }

        /**
         * Decodes the body as {@code application/x-www-form-urlencoded}.
         *
         * @return the form parameters by name, in body order; empty for an empty body
         */
        public Map<String, String> form() {
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
