/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the {@code protocol: websocket} dispatch path end-to-end over the public HTTPS edge,
 * driving the opaque WebSocket relay, the fail-closed {@code Origin} gate (GW-09 / CSWSH), the
 * per-route idle-timeout reclaim, and auth-before-dial rejection against the mounted
 * {@code endpoints/websocket.yaml} routes. Every route relays to the go-httpbin
 * {@code /websocket/echo} upstream (the {@code WS_UPSTREAM} topology alias).
 * <p>
 * The suite is a black-box client: it opens real {@code wss://} handshakes with the JDK
 * {@link java.net.http.WebSocket} client (trust-all TLS for the stack's self-signed localhost
 * certificate, mirroring {@link BaseIntegrationTest}'s relaxed REST Assured validation). Following
 * the {@link BearerValidationIT} precedent — the black-box suite holds no signing key, so it cannot
 * mint a valid bearer token — the <em>successful</em> echo round-trip is driven over a public WS
 * route with a populated {@code allowed_origins}, while the bearer WS route is exercised through its
 * <em>rejection</em> path: a tokenless handshake is rejected {@code 401} at the offline bearer stage
 * before the {@code Origin} gate and before any upstream dial. Together the routes cover every WS
 * behaviour: opaque relay, Origin enforcement, auth-before-dial, idle-reclaim-vs-heartbeat, and the
 * unmatched-path {@code 404}. The remaining fail-closed contract — a bearer WS route booting with an
 * empty {@code allowed_origins} — cannot coexist with a bootable stack (it aborts boot fail-fast), so
 * it is proven by {@code verify-invalid-config-fails.sh} and the unit-level {@code ConfigValidatorTest}
 * rather than against the running edge here.
 * <p>
 * <strong>Admission-permit exhaustion.</strong> One test in this suite deliberately drives a
 * <em>different</em> instance: {@code api-sheriff-ws-admission} on {@code test.ws.admission.port}
 * (10447), whose overlaid {@code gateway.yaml} shrinks the {@code edge_hardening} budget to single
 * digits. The permit-release contract cannot be observed on the primary instance at all — its
 * production-shaped caps (2048 general permits, 512 relay permits) would need thousands of sequential
 * upgrades to exhaust, so a handful of connections passes there whether or not the relay ever returns
 * its permits. The low-cap instance is what turns that silent pass into a real assertion.
 * <p>
 * <strong>Why the pre-existing suite could not see the leak.</strong> Before that regression landed,
 * this class held seven tests, all green, while the relay leaked its admission permit on every
 * established connection. The blind spot was structural, not an oversight in any one test. Each of
 * the seven performs at most <em>one</em> handshake and asserts only the status of that one
 * request — and only three of them complete an upgrade at all
 * ({@link #echoRoundTripThroughGateway()}, {@link #idleRelayReclaimedAfterTimeout()},
 * {@link #heartbeatedRelaySurvivesIdleTimeout()}). The other four are rejection paths that end the
 * HTTP response, so the edge's own end handler releases their permit correctly; they could not leak
 * even in principle. Three leaked permits against a 2048-permit pool is invisible by construction: no
 * assertion in the suite reads the pool, and the pool never runs dry.
 * <p>
 * The generalisable rule: <em>a per-request correctness assertion cannot observe cumulative resource
 * exhaustion.</em> Detecting it needs an assertion of a different shape — one made <em>after N
 * operations</em>, and made <em>about the gateway</em> rather than about any single response. That is
 * exactly the shape of {@link #sequentialRelaysReturnTheirAdmissionPermitsAtTeardown()}: N sequential
 * relays, then a claim about the edge's remaining capacity. Adding an eighth per-request test would
 * have left the leak equally invisible.
 */
class WebSocketProxyIT extends BaseIntegrationTest {

    /** The one Origin the {@code ws-echo} / {@code ws-bearer} routes allow-list (websocket.yaml). */
    private static final String ALLOWED_ORIGIN = "https://sheriff.test";

    /** A foreign Origin that must be rejected by the fail-closed Origin gate. */
    private static final String FOREIGN_ORIGIN = "https://evil.example";

    private static final int HANDSHAKE_TIMEOUT_SECONDS = 15;
    private static final int WEBSOCKET_IDLE_TIMEOUT_SECONDS = 2;
    /** Idle-reclaim close code (WebSocket 1001 Going Away) the relay emits on idle expiry. */
    private static final int CLOSE_GOING_AWAY = 1001;

    /**
     * Sequential upgrades driven against the low-cap instance. It exceeds BOTH caps that instance
     * declares ({@code admission_cap: 8}, {@code websocket_relay_cap: 2}), so a leak of either permit
     * fails the run: the relay sub-budget would run dry on the 3rd upgrade and the general budget on
     * the 9th. With both permits returned at relay teardown, all ten succeed.
     */
    private static final int LOW_CAP_SEQUENTIAL_UPGRADES = 10;

    private static String wsBaseUri;
    private static String lowCapWsBaseUri;
    private static int lowCapHttpPort;
    private static HttpClient httpClient;

    @BeforeAll
    static void setUpWebSocketClient() throws Exception {
        String testPort = System.getProperty("test.https.port", "10443");
        wsBaseUri = "wss://localhost:" + testPort;

        // The dedicated low-admission-budget instance (api-sheriff-ws-admission), published on its
        // own host port and mounting the sheriff-config-ws-admission gateway.yaml overlay. It shares
        // the endpoints/ directory with the primary instance, so /ws/echo and /proxy resolve here too.
        lowCapHttpPort = Integer.parseInt(System.getProperty("test.ws.admission.port", "10447"));
        lowCapWsBaseUri = "wss://localhost:" + lowCapHttpPort;

        // Trust-all TLS: the integration stack serves a self-signed localhost certificate, exactly
        // the case BaseIntegrationTest handles for REST Assured via useRelaxedHTTPSValidation(). The
        // JDK WebSocket client offers no equivalent one-liner, so a trust-all context is built here.
        // Scoped to this black-box IT against a throwaway local certificate — never production trust.
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
        httpClient = HttpClient.newBuilder().sslContext(sslContext).build();
    }

    @Test
    @DisplayName("an allow-listed Origin handshake upgrades and the relay round-trips an echo frame")
    void echoRoundTripThroughGateway() throws Exception {
        var listener = new RecordingListener();
        WebSocket socket = httpClient.newWebSocketBuilder()
                .header("Origin", ALLOWED_ORIGIN)
                .buildAsync(URI.create(wsBaseUri + "/ws/echo"), listener)
                .get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try {
            socket.sendText("sheriff-ws-echo", true).get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String echoed = listener.firstMessage.get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals("sheriff-ws-echo", echoed, "the opaque relay must round-trip the text frame verbatim");
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    @Test
    @DisplayName("a tokenless handshake on a bearer WS route is rejected 401 before any upstream dial")
    void unauthenticatedBearerHandshakeRejected() {
        WebSocketHandshakeException failure = expectHandshakeFailure("/ws/bearer", ALLOWED_ORIGIN);
        assertEquals(401, failure.getResponse().statusCode(),
                "a bearer WS route must reject a missing token 401 at the auth stage, before the Origin gate and dial");
    }

    @Test
    @DisplayName("a foreign-Origin handshake is rejected 403 by the fail-closed Origin gate before dial")
    void foreignOriginHandshakeRejected() {
        WebSocketHandshakeException failure = expectHandshakeFailure("/ws/echo", FOREIGN_ORIGIN);
        assertEquals(403, failure.getResponse().statusCode(),
                "a foreign Origin must be rejected 403 (GW-09 / CSWSH) before the upstream is dialed");
    }

    @Test
    @DisplayName("an absent-Origin handshake is rejected 403 by the fail-closed Origin gate before dial")
    void absentOriginHandshakeRejected() {
        WebSocketHandshakeException failure = expectHandshakeFailure("/ws/echo", null);
        assertEquals(403, failure.getResponse().statusCode(),
                "an absent Origin must be rejected 403 — there is no any-origin default");
    }

    @Test
    @DisplayName("an idle established relay is reclaimed with close 1001 after idle_timeout_seconds")
    void idleRelayReclaimedAfterTimeout() throws Exception {
        var listener = new RecordingListener();
        WebSocket socket = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(wsBaseUri + "/ws/idle"), listener)
                .get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try {
            // Send nothing: the relay must reclaim the idle connection after idle_timeout_seconds (2s).
            int closeCode = listener.closed.get(WEBSOCKET_IDLE_TIMEOUT_SECONDS + 8, TimeUnit.SECONDS);
            assertEquals(CLOSE_GOING_AWAY, closeCode, "an idle relay must be reclaimed with WebSocket close 1001");
        } finally {
            socket.abort();
        }
    }

    @Test
    @DisplayName("a relay kept warm by periodic frames survives past the idle timeout")
    void heartbeatedRelaySurvivesIdleTimeout() throws Exception {
        var listener = new RecordingListener();
        WebSocket socket = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(wsBaseUri + "/ws/idle"), listener)
                .get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try {
            // Beat well inside the 2s idle window for ~5s (2.5x the idle timeout); every frame resets
            // the relay's idle timer, so the connection must NOT be reclaimed.
            for (int beat = 0; beat < 6; beat++) {
                socket.sendText("beat-" + beat, true).get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                TimeUnit.MILLISECONDS.sleep(800);
            }
            assertFalse(listener.closed.isDone(),
                    "a heartbeated relay must survive past the idle timeout — it was reclaimed");
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    @Test
    @DisplayName("a handshake to an unmatched WS path is rejected 404 by deny-by-default route selection")
    void unmatchedWebSocketPathRejected() {
        WebSocketHandshakeException failure = expectHandshakeFailure("/ws/does-not-exist", ALLOWED_ORIGIN);
        assertEquals(404, failure.getResponse().statusCode(),
                "an unmatched WS path must be rejected 404 by deny-by-default route selection");
    }

    @Test
    @DisplayName("more sequential relays than either admission cap allows all succeed, and HTTP still serves")
    void sequentialRelaysReturnTheirAdmissionPermitsAtTeardown() throws Exception {
        for (int upgrade = 1; upgrade <= LOW_CAP_SEQUENTIAL_UPGRADES; upgrade++) {
            var listener = new RecordingListener();
            WebSocket socket = openLowCapRelay(listener, upgrade);
            String payload = "sheriff-ws-admission-" + upgrade;

            socket.sendText(payload, true).get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String echoed = listener.firstMessage.get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            // Awaiting the close frame is what makes the next iteration race-free rather than timed:
            // the relay's single teardown funnel releases both permits BEFORE it closes either leg,
            // so a close frame observed here proves the release has already happened.
            listener.closed.get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals(payload, echoed,
                    "relay " + upgrade + " must round-trip its frame — a permit-starved edge cannot echo");
        }

        // A leaked general permit starves ordinary HTTP long before it exhausts the pool entirely, so
        // the surviving HTTP path is the second half of the proof: the ten relays above returned their
        // general permits too, not just their relay sub-permits.
        given()
                .port(lowCapHttpPort)
                .when()
                .get("/proxy/get")
                .then()
                .statusCode(200);
    }

    /**
     * Opens one relay against the low-cap instance, translating a refused upgrade into an assertion
     * failure that names which upgrade in the sequence was refused — the raw
     * {@link ExecutionException} would otherwise report only a bare {@code 503} with no indication of
     * whether the run died at the relay sub-budget or the general one.
     */
    private static WebSocket openLowCapRelay(RecordingListener listener, int upgrade) throws Exception {
        try {
            return httpClient.newWebSocketBuilder()
                    .header("Origin", ALLOWED_ORIGIN)
                    .buildAsync(URI.create(lowCapWsBaseUri + "/ws/echo"), listener)
                    .get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException refused) {
            throw new AssertionError("upgrade " + upgrade + " of " + LOW_CAP_SEQUENTIAL_UPGRADES
                    + " was refused — the closed relays before it did not return their admission permits",
                    refused.getCause());
        }
    }

    /**
     * Opens a handshake expected to fail (non-101) and returns the underlying
     * {@link WebSocketHandshakeException} so the caller can assert the HTTP status. An {@code origin}
     * of {@code null} omits the {@code Origin} header entirely (the absent-Origin case).
     */
    private static WebSocketHandshakeException expectHandshakeFailure(String path, String origin) {
        WebSocket.Builder builder = httpClient.newWebSocketBuilder();
        if (origin != null) {
            builder.header("Origin", origin);
        }
        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> builder.buildAsync(URI.create(wsBaseUri + path), new RecordingListener())
                        .get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the handshake to " + path + " was expected to fail");
        assertInstanceOf(WebSocketHandshakeException.class, thrown.getCause(),
                "a rejected WebSocket handshake must surface a WebSocketHandshakeException");
        return (WebSocketHandshakeException) thrown.getCause();
    }

    /**
     * Captures the first fully-assembled text message and the close status code, so a test can await
     * the echo round-trip and observe the idle-reclaim close.
     */
    private static final class RecordingListener implements WebSocket.Listener {

        private final CompletableFuture<String> firstMessage = new CompletableFuture<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                firstMessage.complete(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            firstMessage.completeExceptionally(error);
            closed.completeExceptionally(error);
        }
    }

    /**
     * A trust-all {@link X509TrustManager} for the stack's self-signed localhost certificate. Scoped
     * strictly to this black-box integration test — never a production trust decision.
     */
    private static final class TrustAllManager implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // Trust-all test manager: the local stack's self-signed certificate is intentionally accepted.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // Trust-all test manager: the local stack's self-signed certificate is intentionally accepted.
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
