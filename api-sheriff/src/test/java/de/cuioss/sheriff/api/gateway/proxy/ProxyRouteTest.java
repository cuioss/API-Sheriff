/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.gateway.proxy;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;


import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.cuioss.test.mockwebserver.EnableMockWebServer;
import de.cuioss.test.mockwebserver.dispatcher.HttpMethodMapper;
import de.cuioss.test.mockwebserver.dispatcher.ModuleDispatcher;
import de.cuioss.test.mockwebserver.dispatcher.ModuleDispatcherElement;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import lombok.NonNull;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okio.ByteString;

/**
 * Tests for {@link ProxyRoute}: forwarding of method, path remainder, query and
 * body to the upstream, hop-by-hop header stripping, {@code 404}
 * deny-by-default for unmatched paths, and the two disposable stopgap mitigations
 * (a {@code BodyHandler} request-body size limit and an {@code HttpRequest}
 * upstream timeout).
 * <p>
 * The proxy edge sources its upstream from the {@code RouteTable} the
 * configuration subsystem assembles at boot: the test config
 * ({@code src/test/resources/config/testboot}) declares a single {@code /proxy}
 * route ({@code endpoints/proxy.yaml}) whose {@code UPSTREAM} topology alias
 * ({@code topology.properties}) resolves to the {@link MockWebServer} started on
 * the fixed port below. There is no static {@code ProxyConfiguration} bean any
 * more. The fixed port is required because {@code @QuarkusTest} freezes config at
 * boot, so a dynamically-assigned port cannot be injected. The mock echoes each
 * received request back as JSON, so the assertions verify exactly what the gateway
 * forwarded from the RouteTable-sourced upstream.
 */
@QuarkusTest
@EnableMockWebServer(manualStart = true)
@ModuleDispatcher
class ProxyRouteTest {

    /** Must match the {@code UPSTREAM} alias port in {@code config/testboot/topology.properties}. */
    private static final int UPSTREAM_PORT = 19191;

    /**
     * Mirrors {@code ProxyRoute}'s test-only upstream-timeout override property. Kept as a literal
     * (not shared code) so the test pins the exact contract the production seam exposes.
     */
    private static final String UPSTREAM_TIMEOUT_MS_PROPERTY = "apisheriff.proxy.upstream-request-timeout-ms";

    /** Path remainder the {@link EchoDispatcher} answers slowly, to trip the upstream request timeout. */
    private static final String SLOW_MARKER = "slow-upstream";

    @BeforeAll
    static void setup() {
        RestAssured.useRelaxedHTTPSValidation();
    }

    @BeforeEach
    void startUpstream(MockWebServer upstream) throws IOException {
        upstream.start(UPSTREAM_PORT);
    }

    public ModuleDispatcherElement getModuleDispatcher() {
        return new EchoDispatcher();
    }

    @Test
    void shouldForwardMethodPathAndQuery() {
        var response = given()
                .when().get("/proxy/orders/42?page=2&size=10")
                .then()
                .statusCode(200)
                .extract();

        assertEquals("GET", response.path("method"));
        assertEquals("/orders/42?page=2&size=10", response.path("target"));
    }

    @Test
    void shouldForwardBodyOnPost() {
        var response = given()
                .contentType("text/plain")
                .body("hello-upstream")
                .when().post("/proxy/submit")
                .then()
                .statusCode(200)
                .extract();

        assertEquals("POST", response.path("method"));
        assertEquals("/submit", response.path("target"));
        assertEquals("hello-upstream", response.path("body"));
    }

    @Test
    void shouldForwardCustomHeaderButStripHopByHop() {
        var response = given()
                .header("X-Forward-Test", "custom-value")
                .header("Connection", "keep-alive")
                .when().get("/proxy/echo")
                .then()
                .statusCode(200)
                .extract();

        assertEquals("custom-value", response.path("customHeader"));
        assertEquals("", response.path("connectionHeader"));
    }

    @Test
    void shouldReturn404ForUnmatchedPath() {
        given()
                .when().get("/not-proxied/resource")
                .then()
                .statusCode(404);
    }

    @Test
    void shouldReturn502WhenUpstreamUnavailable(MockWebServer upstream) throws Exception {
        // Take the upstream down so the forward fails with a connection error
        upstream.close();
        given()
                .when().get("/proxy/unavailable")
                .then()
                .statusCode(502);
    }

    @Test
    void shouldRejectRequestBodyExceedingSizeLimit() {
        // BodyHandler.setBodyLimit(1 MiB): a body one byte over the limit is rejected
        // by Vert.x with 413 before the proxy ever forwards it upstream.
        String overLimitBody = "a".repeat(1024 * 1024 + 1);
        given()
                .contentType("text/plain")
                .body(overLimitBody)
                .when().post("/proxy/submit")
                .then()
                .statusCode(413);
    }

    @Test
    void shouldReturn502WhenUpstreamExceedsRequestTimeout() {
        // Override the upstream request timeout to a short value so the slow upstream
        // (EchoDispatcher delays the SLOW_MARKER path well beyond it) trips the
        // HttpRequest timeout, which surfaces as a 502 — without a multi-second wait.
        System.setProperty(UPSTREAM_TIMEOUT_MS_PROPERTY, "500");
        try {
            given()
                    .when().get("/proxy/" + SLOW_MARKER)
                    .then()
                    .statusCode(502);
        } finally {
            System.clearProperty(UPSTREAM_TIMEOUT_MS_PROPERTY);
        }
    }

    @Test
    void shouldFallBackToDefaultTimeoutWhenOverrideNonPositive() {
        // A misconfigured non-positive override (0 or negative) must be rejected in favour
        // of the default timeout rather than producing a non-positive Duration, which
        // HttpRequest.Builder.timeout() would reject with IllegalArgumentException — turning
        // every proxied request into a 502. With the guard, a normal fast request still
        // forwards and returns 200.
        System.setProperty(UPSTREAM_TIMEOUT_MS_PROPERTY, "0");
        try {
            given()
                    .when().get("/proxy/orders/42")
                    .then()
                    .statusCode(200);
        } finally {
            System.clearProperty(UPSTREAM_TIMEOUT_MS_PROPERTY);
        }
    }

    /**
     * Echoes each received request back as JSON so the test can assert exactly
     * what the gateway forwarded. Matches every forwarded path ({@code "/"} base URL).
     * A request whose target contains {@link #SLOW_MARKER} is answered with a long
     * header delay so the proxy's upstream request timeout trips first.
     */
    private static final class EchoDispatcher implements ModuleDispatcherElement {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        @Override
        public String getBaseUrl() {
            return "/";
        }

        @Override
        public Optional<MockResponse> handleGet(@NonNull RecordedRequest request) {
            return echo(request);
        }

        @Override
        public Optional<MockResponse> handlePost(@NonNull RecordedRequest request) {
            return echo(request);
        }

        @Override
        public Optional<MockResponse> handlePut(@NonNull RecordedRequest request) {
            return echo(request);
        }

        @Override
        public Optional<MockResponse> handleDelete(@NonNull RecordedRequest request) {
            return echo(request);
        }

        @Override
        public @NonNull Set<HttpMethodMapper> supportedMethods() {
            return Set.of(HttpMethodMapper.values());
        }

        private Optional<MockResponse> echo(RecordedRequest request) {
            ByteString requestBody = request.getBody();
            // Absent headers are echoed as "" (not null): the stripped hop-by-hop
            // Connection header therefore surfaces as an empty string in the assertion.
            Map<String, Object> payload = Map.of(
                    "method", request.getMethod(),
                    "target", request.getTarget(),
                    "body", requestBody == null ? "" : requestBody.utf8(),
                    "customHeader", Optional.ofNullable(request.getHeaders().get("X-Forward-Test")).orElse(""),
                    "connectionHeader", Optional.ofNullable(request.getHeaders().get("Connection")).orElse(""));
            try {
                MockResponse.Builder builder = new MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body(MAPPER.writeValueAsString(payload));
                String target = request.getTarget();
                if (target != null && target.contains(SLOW_MARKER)) {
                    // Far longer than the overridden request timeout, so the proxy
                    // times out and returns 502 before this response is delivered.
                    builder.headersDelay(5, TimeUnit.SECONDS);
                }
                return Optional.of(builder.build());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialise echo payload", e);
            }
        }
    }
}
