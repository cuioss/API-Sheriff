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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCalls;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import de.cuioss.sheriff.gateway.integration.grpc.EchoGrpc;
import de.cuioss.sheriff.gateway.integration.grpc.EchoRequest;
import de.cuioss.sheriff.gateway.integration.grpc.EchoResponse;
import de.cuioss.sheriff.gateway.integration.grpc.SecureEchoGrpc;

import io.restassured.http.Header;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves against the native image on the shipped default ({@code security_defaults.uniform_not_found}
 * absent, hence on) that the endpoint-existence oracle is closed for an untrusted caller (ADR-0073):
 * every rejection raised after route selection and before the trust boundary is answered exactly as
 * an address no route serves, so the answer does not tell a caller whether the route exists.
 * <p>
 * <strong>The comparison.</strong> Each pre-trust case below is sent to a bearer route and, with the
 * same method, headers and body, to {@link UniformNotFoundAssertions#UNROUTED_PATH}. The two answers
 * must agree on the status, the body, the {@code Content-Type} and the set of response-header names —
 * names, not values, because {@code Date} differs between any two answers. The comparison is made
 * twice: as a JSON client, and as a browser navigation ({@code Accept: text/html}), which the portal's
 * {@code error_pages: true} answers with the HTML error page. The cases are a selection of the pre-trust
 * rejections a bearer route can raise: no token, a garbage token, a method outside
 * {@code allowed_methods}, a path outside {@code allowed_paths}, a declared body over the route's
 * inherited strict cap, and a {@code protocol: grpc} route, which is answered with the HTTP {@code 404}
 * and never the trailers-only status a gRPC rejection otherwise carries. The list is not exhaustive:
 * the route-scoped security-filter refusal ({@code SECURITY_FILTER_VIOLATION}) and the CSRF and cookie
 * same-origin refusals ({@code CSRF_REJECTED}) are not compared here. The byte comparison of the filter
 * refusal and of the session CSRF refusal against the unrouted address is made in-process by
 * {@code GatewayEdgeUniformNotFoundTest}; on the IT stack the filter refusal is held to the unrouted
 * problem document and its true event by {@code StrictQueryAcceptanceIT} and
 * {@code SecurityProfileModeIT}.
 * <p>
 * <strong>What is still reported.</strong> The cause is withheld from the caller only: every case moves
 * {@code sheriff_errors_total} under its TRUE event on the route it selected, and never under
 * {@code NO_ROUTE_MATCHED}.
 * <p>
 * <strong>Crafted tokens.</strong> A bearer route is fed tokens shaped to make the token library fail
 * in an unusual way: an undecodable token, a token with a segment missing, an unsigned {@code alg: none}
 * token, a well-formed token naming a key id the issuer does not publish, a token whose claims carry
 * the wrong JSON types, and a well-formed token as long as the {@code Authorization} carve-out admits.
 * Each is answered with the unrouted {@code 404} and reported as {@code TOKEN_INVALID} — never as
 * {@code PRE_TRUST_INTERNAL_FAILURE}, which would mean the library failed instead of refusing.
 * <p>
 * <strong>Timing.</strong> No unrouted {@code 404}, genuine or resolved, leaves the edge before the
 * response-timing floor. The floor is {@link #RESPONSE_FLOOR}, restated here as the shipped constant a
 * black-box caller observes; it is asserted as a lower bound on the round trip only, so a slow runner
 * cannot make the assertion flaky. The floor is also <em>measured</em>: eight enumerated pre-trust
 * paths a caller can drive on this stack are each probed {@link #MEASUREMENT_PROBES} times in sequence, and
 * {@code sheriff_uniform_not_found_overruns_total} must not move — no probe was released after the
 * first floor step. A run on a loaded machine can move it without the floor being too low; only an
 * overrun that reproduces on a quiet run is evidence for raising the floor.
 * <p>
 * <strong>What stays honest.</strong> A caller past the trust boundary keeps the honest answer: a
 * validated token lacking the route's scope is refused {@code 403}. The session branch keeps its own
 * negotiation: an XHR without a session is answered {@code 401 application/problem+json}, a navigation
 * is redirected {@code 302} into the identity provider.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("The endpoint-existence oracle is closed for untrusted callers, and honest past the trust boundary")
class EnumerationOracleIT extends BaseIntegrationTest {

    /**
     * {@code UniformNotFound.RESPONSE_FLOOR} of the shipped image: no unrouted {@code 404} leaves the
     * edge earlier than this after admission. Restated as the observable contract, like the breaker's
     * open delay in {@link HtmlErrorPageIT}.
     */
    private static final Duration RESPONSE_FLOOR = Duration.ofMillis(50);

    /** The {@code secure-proxy} route: {@code require: bearer}, the strict preset, no endpoint scope. */
    private static final String BEARER_PATH = "/secure/get";
    private static final String BEARER_ROUTE = "secure-proxy";

    /** One segment deeper than the {@code secure-filtered} route's {@code allowed_paths} admits. */
    private static final String OFF_ALLOWLIST_PATH = "/secure/filtered/deep/path";
    private static final String FILTERED_ROUTE = "secure-filtered";

    /** The proto package of the echo services the {@code grpc.yaml} routes match on. */
    private static final String GRPC_SERVICE = "de.cuioss.sheriff.api.integration.grpc";

    /** The bearer {@code protocol: grpc} route, on its own {@code SecureEcho} service path. */
    private static final String GRPC_BEARER_PATH = "/" + GRPC_SERVICE + ".SecureEcho/Unary";
    private static final String GRPC_BEARER_ROUTE = "grpc-bearer";

    /** The {@code secure-scoped} route, which needs a scope beyond {@code oidc.scopes}. */
    private static final String SCOPED_BEARER_PATH = "/secure/scoped/get";

    /** The plain {@code require: session} route. */
    private static final String SESSION_PATH = "/app/get";

    /** One byte over the strict preset's inherited 1 MiB body cap of the bearer route. */
    private static final int OVER_STRICT_BODY_CAP = 1048577;

    private static final String JSON_ACCEPT = "application/json";
    private static final String BROWSER_ACCEPT = "text/html";

    private static final int CALL_TIMEOUT_SECONDS = 15;

    /** The declared {@code security_defaults.max_authorization_header_value_length} of the stack. */
    private static final int AUTHORIZATION_VALUE_CAP = 8192;

    private static final String BEARER = "Bearer ";

    /** The uniform-404 overrun counter; it carries no label (ADR-0073). */
    private static final String OVERRUNS_TOTAL = "sheriff_uniform_not_found_overruns_total";

    /** The event a pre-trust internal failure is reported under. */
    private static final String PRE_TRUST_INTERNAL_FAILURE = "PRE_TRUST_INTERNAL_FAILURE";

    /** How often each pre-trust path is probed, one request after the other, in the floor measurement. */
    private static final int MEASUREMENT_PROBES = 20;

    /** An unsafe-method path on the {@code bff-session-fallback} route, where the CSRF defence runs. */
    private static final String CSRF_PATH = "/app/fallback/post";

    /** An {@code Origin} that is neither the gateway origin nor a configured CSRF trusted origin. */
    private static final String FOREIGN_ORIGIN = "https://evil.example.com";

    private static final Pattern KEY_ID = Pattern.compile("\"kid\"\\s*:\\s*\"[^\"]*\"");

    private static ManagedChannel channel;

    /**
     * One pre-trust rejection on a bearer route.
     *
     * @param name      names the case in test output
     * @param method    the HTTP method
     * @param path      the routed path the rejection is raised on
     * @param headers   the request headers, beside {@code Accept}
     * @param body      the request body, {@code null} for none
     * @param routeId   the config-fixed id of the route the request selects
     * @param trueEvent the event the rejection is reported under
     */
    record PreTrustCase(String name, String method, String path, Map<String, String> headers, byte[] body,
    String routeId, String trueEvent) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<PreTrustCase> preTrustCases() {
        byte[] overCap = new byte[OVER_STRICT_BODY_CAP];
        Arrays.fill(overCap, (byte) 'x');
        return Stream.of(
                new PreTrustCase("no token", "GET", BEARER_PATH, Map.of(), null, BEARER_ROUTE, "TOKEN_MISSING"),
                new PreTrustCase("garbage token", "GET", BEARER_PATH, Map.of("Authorization", "Bearer not-a-real-jwt"),
                        null, BEARER_ROUTE, "TOKEN_INVALID"),
                new PreTrustCase("wrong method", "PATCH", BEARER_PATH, Map.of(), null, BEARER_ROUTE, "METHOD_NOT_ALLOWED"),
                new PreTrustCase("off-allowlist path", "GET", OFF_ALLOWLIST_PATH, Map.of(), null, FILTERED_ROUTE,
                        "PATH_NOT_ALLOWED"),
                new PreTrustCase("route-scoped oversized body", "POST", BEARER_PATH, Map.of("Content-Type", "text/plain"),
                        overCap, BEARER_ROUTE, "CONTENT_TOO_LARGE"),
                new PreTrustCase("protocol: grpc route", "POST", GRPC_BEARER_PATH,
                        Map.of("Content-Type", "application/grpc"), null, GRPC_BEARER_ROUTE, "TOKEN_MISSING"));
    }

    /**
     * One crafted {@code Authorization} value for the bearer route.
     *
     * @param name          names the case in test output
     * @param authorization the header value sent
     */
    record CraftedToken(String name, String authorization) {

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * The crafted tokens. All but the undecodable one start from a token the realm really issued, so
     * every other part of them is what the library expects and only the crafted part is wrong.
     */
    static Stream<CraftedToken> craftedTokens() {
        JwtParts real = JwtParts.of(BearerValidationIT.mintIntegrationRealmAccessToken(BearerValidationIT.OIDC_SCOPE));
        return Stream.of(
                new CraftedToken("undecodable segments", BEARER + "!!!.@@@.###"),
                new CraftedToken("a segment missing", BEARER + real.header() + "." + real.payload()),
                new CraftedToken("alg none",
                        BEARER + encode("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "." + real.payload() + "."),
                new CraftedToken("unknown key id", unknownKeyId(real)),
                new CraftedToken("wrong claim types", BEARER + real.header() + "."
                        + encode("{\"iss\":42,\"sub\":[\"x\"],\"exp\":\"never\",\"iat\":true,\"aud\":{\"x\":1},\"scope\":7}")
                        + "." + real.signature()),
                new CraftedToken("at the Authorization cap", atAuthorizationCap(real)));
    }

    @BeforeAll
    static void setUpGrpcChannel() throws Exception {
        int testPort = Integer.parseInt(System.getProperty("test.https.port", "10443"));
        // Trust-all TLS over ALPN h2 for the stack's self-signed localhost certificate, as GrpcProxyIT
        // dials the same edge. Scoped to this black-box test against a throwaway local certificate.
        channel = NettyChannelBuilder.forAddress("localhost", testPort)
                .overrideAuthority("localhost")
                .sslContext(GrpcSslContexts.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build())
                .build();
    }

    @AfterAll
    static void tearDownGrpcChannel() throws Exception {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("preTrustCases")
    @DisplayName("a JSON client gets the unrouted answer: same status, body, Content-Type and header names")
    void problemAnswerIsTheUnroutedOne(PreTrustCase rejection) {
        Timed rejected = send(rejection, rejection.path(), JSON_ACCEPT);
        Timed unrouted = send(rejection, UniformNotFoundAssertions.UNROUTED_PATH, JSON_ACCEPT);

        UniformNotFoundAssertions.assertUnroutedProblem(rejected.response(), rejection.name());
        assertSameAnswer(rejection, unrouted, rejected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("preTrustCases")
    @DisplayName("a browser navigation gets the unrouted HTML error page: same status, body, Content-Type and header names")
    void htmlAnswerIsTheUnroutedPage(PreTrustCase rejection) {
        Timed rejected = send(rejection, rejection.path(), BROWSER_ACCEPT);
        Timed unrouted = send(rejection, UniformNotFoundAssertions.UNROUTED_PATH, BROWSER_ACCEPT);

        assertAll(rejection.name(),
                () -> assertEquals(404, rejected.response().statusCode()),
                () -> assertTrue(String.valueOf(rejected.response().contentType()).startsWith(BROWSER_ACCEPT),
                        () -> "a navigation gets the HTML error page; content type was "
                                + rejected.response().contentType()),
                () -> assertTrue(rejected.response().asString().contains(PortalIT.OPERATOR_TEMPLATE_MARKER),
                        "the page is the operator's error page"));
        assertSameAnswer(rejection, unrouted, rejected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("preTrustCases")
    @DisplayName("the true event is metered under the selected route, never as NO_ROUTE_MATCHED")
    void theTrueEventIsMetered(PreTrustCase rejection) {
        ExtractableResponse<Response> response = UniformNotFoundAssertions.assertTrueEventMetered(
                rejection.routeId(), rejection.trueEvent(), () -> send(rejection, rejection.path(), JSON_ACCEPT).response());

        assertEquals(404, response.statusCode(), "precondition: the caller is shown the unrouted 404");
    }

    @Test
    @DisplayName("a gRPC client is answered alike: a tokenless bearer call and an unrouted service fail with the same status")
    void grpcClientCannotTellTheBearerServiceFromAnUnroutedOne() {
        EchoRequest request = EchoRequest.newBuilder().setMessage("oracle").build();
        MethodDescriptor<EchoRequest, EchoResponse> unroutedService = EchoGrpc.getUnaryMethod().toBuilder()
                .setFullMethodName(MethodDescriptor.generateFullMethodName(GRPC_SERVICE + ".NoSuchService", "Unary"))
                .build();

        StatusRuntimeException bearer = assertThrows(StatusRuntimeException.class,
                () -> SecureEchoGrpc.newBlockingStub(channel).withDeadlineAfter(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .unary(request),
                "a tokenless call on the bearer gRPC route must be refused before any upstream dial");
        StatusRuntimeException unrouted = assertThrows(StatusRuntimeException.class,
                () -> ClientCalls.blockingUnaryCall(channel, unroutedService,
                        CallOptions.DEFAULT.withDeadlineAfter(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS), request),
                "a call on a service no route serves must be refused");

        assertAll("the gRPC client's view of the two refusals",
                () -> assertEquals(Status.Code.UNIMPLEMENTED, unrouted.getStatus().getCode(),
                        "an HTTP 404 surfaces to a gRPC client as UNIMPLEMENTED"),
                () -> assertEquals(unrouted.getStatus().getCode(), bearer.getStatus().getCode(),
                        "the tokenless bearer call must surface exactly as the unrouted service does, never as the"
                                + " trailers-only UNAUTHENTICATED that would disclose the route"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("craftedTokens")
    @DisplayName("a crafted bearer token is refused as the unrouted 404 and reported as TOKEN_INVALID, never as an internal failure")
    void craftedTokenIsRefusedNotFailedOn(CraftedToken crafted) {
        String before = PrometheusScrape.scrape();

        ExtractableResponse<Response> response = UniformNotFoundAssertions.assertTrueEventMetered(BEARER_ROUTE,
                "TOKEN_INVALID", () -> given()
                        .header("Authorization", crafted.authorization())
                        .header("Accept", JSON_ACCEPT)
                        .when()
                        .get(BEARER_PATH)
                        .then()
                        .extract());

        String after = PrometheusScrape.scrape();
        UniformNotFoundAssertions.assertUnroutedProblem(response, crafted.name());
        assertEquals(PrometheusScrape.errorCount(before, BEARER_ROUTE, PRE_TRUST_INTERNAL_FAILURE),
                PrometheusScrape.errorCount(after, BEARER_ROUTE, PRE_TRUST_INTERNAL_FAILURE),
                () -> crafted.name() + " must be refused by the token library, not make it fail");
    }

    /**
     * The measurement of the floor: eight enumerated pre-trust paths a caller can drive on this stack
     * are each probed {@link #MEASUREMENT_PROBES} times, one request after the other, and the overrun counter must not
     * move. The slowest round trip of each path is reported when it does, so a failure names the path.
     */
    @Test
    @DisplayName("the floor is measured: no caller-drivable pre-trust path is released after the first floor step")
    void noPreTrustPathOverrunsTheFloor() {
        JwtParts real = JwtParts.of(BearerValidationIT.mintIntegrationRealmAccessToken(BearerValidationIT.OIDC_SCOPE));
        List<Probe> probes = List.of(
                probe("unrouted address", "GET", UniformNotFoundAssertions.UNROUTED_PATH, Map.of()),
                probe("verb gate", "PATCH", BEARER_PATH, Map.of()),
                probe("path not allowed", "GET", OFF_ALLOWLIST_PATH, Map.of()),
                probe("missing token", "GET", BEARER_PATH, Map.of()),
                probe("malformed token", "GET", BEARER_PATH, Map.of("Authorization", BEARER + "not-a-real-jwt")),
                probe("token at the Authorization cap", "GET", BEARER_PATH,
                        Map.of("Authorization", atAuthorizationCap(real))),
                probe("unknown key id", "GET", BEARER_PATH, Map.of("Authorization", unknownKeyId(real))),
                new Probe("session-branch CSRF refusal", () -> BffKeycloakLoginFlow.gateway(Map.of())
                        .header("Origin", FOREIGN_ORIGIN)
                        .header("Accept", JSON_ACCEPT)
                        .redirects().follow(false)
                        .when()
                        .post(CSRF_PATH)
                        .then()
                        .extract()));
        Map<String, Duration> slowest = new LinkedHashMap<>();
        double overrunsBefore = overruns(PrometheusScrape.scrape());

        for (Probe probe : probes) {
            for (int sent = 0; sent < MEASUREMENT_PROBES; sent++) {
                long sentAt = System.nanoTime();
                ExtractableResponse<Response> response = probe.send().get();
                Duration elapsed = Duration.ofNanos(System.nanoTime() - sentAt);
                assertEquals(UniformNotFoundAssertions.UNROUTED_STATUS, response.statusCode(),
                        () -> probe.name() + " must be answered as the unrouted 404");
                slowest.merge(probe.name(), elapsed, (kept, next) -> kept.compareTo(next) >= 0 ? kept : next);
            }
        }

        double overrunsAfter = overruns(PrometheusScrape.scrape());
        assertEquals(overrunsBefore, overrunsAfter,
                () -> "no pre-trust path may be released after the first floor step of " + RESPONSE_FLOOR.toMillis()
                        + " ms; slowest round trip per path: " + slowest.entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue().toMillis() + " ms")
                        .collect(Collectors.joining(", ")));
    }

    @Test
    @DisplayName("past the trust boundary: a validated token without the needed scope is answered 403")
    void validatedTokenWithoutTheScopeIsAnswered403() {
        String token = BearerValidationIT.mintIntegrationRealmAccessToken(BearerValidationIT.OIDC_SCOPE);

        ExtractableResponse<Response> response = given()
                .header("Authorization", "Bearer " + token)
                .header("Accept", JSON_ACCEPT)
                .when()
                .get(SCOPED_BEARER_PATH)
                .then()
                .extract();

        assertAll("the caller is trusted, so the scope refusal is honest",
                () -> assertEquals(403, response.statusCode()),
                () -> assertTrue(String.valueOf(response.header("WWW-Authenticate")).contains("insufficient_scope"),
                        () -> "the RFC 6750 challenge names the missing scope; was " + response.header("WWW-Authenticate")),
                () -> assertNull(response.path("method"), "a refused request must not reach the go-httpbin upstream"));
    }

    @Test
    @DisplayName("the session branch keeps its negotiation: an XHR without a session is answered 401 problem+json")
    void sessionRouteXhrIsAnswered401() {
        ExtractableResponse<Response> response = BffKeycloakLoginFlow.gateway(Map.of())
                .header("Accept", JSON_ACCEPT)
                .redirects().follow(false)
                .when()
                .get(SESSION_PATH)
                .then()
                .extract();

        assertAll("the verify-first clause of the session branch",
                () -> assertEquals(401, response.statusCode()),
                () -> assertTrue(String.valueOf(response.contentType()).startsWith(UniformNotFoundAssertions.PROBLEM_JSON),
                        () -> "content type was " + response.contentType()),
                () -> assertNull(response.header("Location"), "an XHR is challenged, never redirected"));
    }

    @Test
    @DisplayName("the session branch keeps its negotiation: a navigation without a session is redirected 302")
    void sessionRouteNavigationIsRedirected() {
        ExtractableResponse<Response> response = BffKeycloakLoginFlow.gateway(Map.of())
                .header("Accept", BROWSER_ACCEPT)
                .redirects().follow(false)
                .when()
                .get(SESSION_PATH)
                .then()
                .extract();

        assertAll("the login redirect of the session branch",
                () -> assertEquals(302, response.statusCode()),
                () -> assertNotNull(response.header("Location"), "the navigation is sent into the identity provider"));
    }

    /**
     * Asserts the rejected answer is indistinguishable from the unrouted one, and that both waited for
     * the response-timing floor.
     */
    private static void assertSameAnswer(PreTrustCase rejection, Timed unrouted, Timed rejected) {
        assertAll(rejection.name() + ": the resolved answer against the unrouted one",
                () -> assertEquals(unrouted.response().statusCode(), rejected.response().statusCode(), "the status"),
                () -> assertEquals(unrouted.response().asString(), rejected.response().asString(), "the body"),
                () -> assertEquals(unrouted.response().contentType(), rejected.response().contentType(),
                        "the Content-Type"),
                () -> assertEquals(headerNames(unrouted.response()), headerNames(rejected.response()),
                        "the set of response-header names"),
                () -> assertFalse(headerNames(rejected.response()).contains("grpc-status"),
                        "no trailers-only gRPC status"),
                () -> assertTrue(unrouted.elapsed().compareTo(RESPONSE_FLOOR) >= 0,
                        () -> "the genuine unrouted 404 was answered after " + unrouted.elapsed().toMillis()
                                + " ms, before the floor of " + RESPONSE_FLOOR.toMillis() + " ms"),
                () -> assertTrue(rejected.elapsed().compareTo(RESPONSE_FLOOR) >= 0,
                        () -> "the resolved 404 was answered after " + rejected.elapsed().toMillis()
                                + " ms, before the floor of " + RESPONSE_FLOOR.toMillis() + " ms"));
    }

    private static Set<String> headerNames(ExtractableResponse<Response> response) {
        return response.headers().asList().stream()
                .map(Header::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Sends the case's request to {@code path} and measures the round trip. */
    private static Timed send(PreTrustCase rejection, String path, String accept) {
        RequestSpecification spec = given()
                .headers(rejection.headers())
                .header("Accept", accept)
                .redirects().follow(false);
        if (rejection.body() != null) {
            spec = spec.body(rejection.body());
        }
        long sentAt = System.nanoTime();
        ExtractableResponse<Response> response = spec.when().request(rejection.method(), path).then().extract();
        return new Timed(response, Duration.ofNanos(System.nanoTime() - sentAt));
    }

    /** An answer and the round trip it took. */
    private record Timed(ExtractableResponse<Response> response, Duration elapsed) {
    }

    /** One caller-drivable pre-trust path of the floor measurement, and how to send it. */
    private record Probe(String name, Supplier<ExtractableResponse<Response>> send) {
    }

    /** A probe on the primary instance with the given method, path and headers beside {@code Accept}. */
    private static Probe probe(String name, String method, String path, Map<String, String> headers) {
        return new Probe(name, () -> given()
                .headers(headers)
                .header("Accept", JSON_ACCEPT)
                .redirects().follow(false)
                .when()
                .request(method, path)
                .then()
                .extract());
    }

    /** The three base64url segments of a compact JWS. */
    private record JwtParts(String header, String payload, String signature) {

        static JwtParts of(String token) {
            String[] parts = token.split("\\.", -1);
            assertEquals(3, parts.length, "precondition: the minted token is a compact JWS");
            return new JwtParts(parts[0], parts[1], parts[2]);
        }
    }

    /** The realm's token with its header naming a key id the issuer does not publish. */
    private static String unknownKeyId(JwtParts real) {
        String header = decode(real.header());
        Matcher keyId = KEY_ID.matcher(header);
        assertTrue(keyId.find(), () -> "precondition: the realm names its signing key in the token header: " + header);
        String renamed = keyId.replaceFirst(Matcher.quoteReplacement("\"kid\":\"no-such-key-of-the-realm\""));
        return BEARER + encode(renamed) + "." + real.payload() + "." + real.signature();
    }

    /**
     * The realm's token with its payload padded by one extra claim until the whole {@code Authorization}
     * value is as long as the carve-out admits. Unpadded base64url never encodes to a length of the form
     * {@code 4k + 1}, so the value ends on the cap or one character below it.
     */
    private static String atAuthorizationCap(JwtParts real) {
        String payload = decode(real.payload());
        int frame = (BEARER + real.header() + ".." + real.signature()).length();
        int pad = Math.max(0, (AUTHORIZATION_VALUE_CAP - frame) * 3 / 4 - payload.length() - 16);
        String token = paddedToken(real, payload, pad);
        String longer = paddedToken(real, payload, pad + 1);
        while (longer.length() <= AUTHORIZATION_VALUE_CAP) {
            token = longer;
            pad++;
            longer = paddedToken(real, payload, pad + 1);
        }
        int length = token.length();
        assertTrue(length <= AUTHORIZATION_VALUE_CAP && length >= AUTHORIZATION_VALUE_CAP - 1,
                () -> "precondition: the padded token must end at the Authorization cap; it is " + length);
        return token;
    }

    private static String paddedToken(JwtParts real, String payload, int pad) {
        String padded = "{\"pad\":\"" + "a".repeat(pad) + "\"," + payload.substring(1);
        return BEARER + real.header() + "." + encode(padded) + "." + real.signature();
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String segment) {
        return new String(Base64.getUrlDecoder().decode(segment), StandardCharsets.UTF_8);
    }

    /**
     * The value of {@value #OVERRUNS_TOTAL} in an exposition. The counter carries no label, and it is
     * exposed only once it has been recorded, so an absent counter counts as zero.
     */
    private static double overruns(String exposition) {
        return exposition.lines()
                .filter(line -> line.startsWith(OVERRUNS_TOTAL + " ") || line.startsWith(OVERRUNS_TOTAL + "{"))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1).strip()))
                .sum();
    }
}
