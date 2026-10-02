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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
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
 * {@code BffRuntimeProducer.backChannelConfiguration} never sets the token engine's
 * {@code allowInsecureHttp}, so a runtime built that way cannot dial plain HTTP on any leg:
 * {@code DiscoveryResolver} refuses a non-{@code https} issuer before it sends anything, and the
 * token, revocation and pushed-request clients build their request through a handler builder that
 * refuses an {@code http} URI. A fixture that is to be reached by a <em>produced</em> runtime
 * therefore has to speak TLS.
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
 * <h2>What it serves</h2>
 *
 * Each {@link Endpoint} is one entry: its path, its HTTP method, its default answer and the member
 * of the discovery document that names it. A request matching no entry by path and method is
 * answered {@code 404}. A matching request is recorded under its endpoint with its method, headers
 * and body, and is then answered from the endpoint's script when a test has
 * {@linkplain #script(Endpoint, Answer) filled} it, and otherwise from the endpoint's default.
 * <p>
 * The fixture mints no token and validates nothing. The token endpoint refuses every grant by
 * default, because an assertion on the shape of a request needs the request and not a grant. The
 * pushed-authorization-request endpoint accepts every push by default, because a login has to get
 * past it before the runtime sends anything else.
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

    private final MockWebServer server;
    private final X509Certificate rootCertificate;
    private final Map<Endpoint, List<ReceivedRequest>> received = new EnumMap<>(Endpoint.class);
    private final Map<Endpoint, Queue<Answer>> scripts = new EnumMap<>(Endpoint.class);

    private StubIdentityProvider(MockWebServer server, X509Certificate rootCertificate) {
        this.server = server;
        this.rootCertificate = rootCertificate;
        for (Endpoint endpoint : Endpoint.values()) {
            received.put(endpoint, new CopyOnWriteArrayList<>());
            scripts.put(endpoint, new ConcurrentLinkedQueue<>());
        }
    }

    /**
     * Issues the certificate chain and starts the listener.
     *
     * @return the running fixture; close it to stop the listener
     * @throws IOException when the listener cannot be bound
     */
    public static StubIdentityProvider start() throws IOException {
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
        StubIdentityProvider provider = new StubIdentityProvider(server, root.certificate());
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
     * @return the issuer an {@code oidc.issuer} must name to reach this fixture: {@code https}, the
     *         loopback address and the bound port. It is also the issuer the discovery document
     *         declares
     */
    public String issuer() {
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
     * @return the absolute URL the endpoint is served at, as the discovery document names it
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
            if (endpoint.path.equals(path) && endpoint.method.equals(request.getMethod())) {
                received.get(endpoint).add(ReceivedRequest.of(request));
                Answer scripted = scripts.get(endpoint).poll();
                return render(scripted == null ? endpoint.defaultAnswer.apply(issuer()) : scripted);
            }
        }
        return new MockResponse.Builder().code(NOT_FOUND).build();
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
