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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * <em>rejection</em> path: a tokenless handshake is refused at the offline bearer stage before the
 * {@code Origin} gate and before any upstream dial. That refusal is raised before the trust boundary,
 * so the shipped default answers it as the unrouted {@code 404} (ADR-0073) — the same status as the
 * unmatched-path handshake — while the {@code Origin} gate, which runs after the trust boundary, keeps
 * its honest {@code 403}. Together the routes cover every WS
 * behaviour: opaque relay, Origin enforcement, auth-before-dial, idle-reclaim-vs-heartbeat, and the
 * unmatched-path {@code 404}. The remaining fail-closed contract — a bearer WS route booting with an
 * empty {@code allowed_origins} — cannot coexist with a bootable stack (it aborts boot fail-fast), so
 * it is proven by {@code verify-invalid-config-fails.sh} and the unit-level {@code ProtocolRulesTest}
 * rather than against the running edge here.
 * <p>
 * <strong>Admission-permit exhaustion.</strong> Three tests in this suite deliberately drive a
 * <em>different</em> instance: {@code api-sheriff-ws-admission} on {@code test.ws.admission.port}
 * (10447), whose overlaid {@code gateway.yaml} shrinks the {@code edge_hardening} budget to single
 * digits. The permit-release contract cannot be observed on the primary instance at all — its
 * production-shaped caps (2048 general permits, 512 relay permits) would need thousands of sequential
 * upgrades to exhaust, so a handful of connections passes there whether or not the relay ever returns
 * its permits. The low-cap instance is what turns that silent pass into a real assertion.
 * <p>
 * All three run one probe ({@link #runLowCapProbe()}): {@link #LOW_CAP_SEQUENTIAL_UPGRADES} sequential
 * relays, then an ordinary HTTP request that must still be served.
 * {@link #sequentialRelaysReturnTheirAdmissionPermitsAtTeardown()} requires the probe to pass. The
 * other two are its <em>negative controls</em>, one per budget, and each proves the probe can fail:
 * <ul>
 * <li>{@link #probeFailsWhileTheRelaySubBudgetIsHeldAndPassesOnceReleased()} holds
 * {@link #LOW_CAP_RELAY_CAP} established relays. To the admission pool a held relay is the same as a
 * relay that never returned its permit, so the probe's first upgrade must be refused {@code 503}.</li>
 * <li>{@link #probeFailsWhileTheGeneralAdmissionBudgetIsHeldAndPassesOnceReleased()} holds
 * {@link #LOW_CAP_ADMISSION_CAP} in-flight HTTP requests and no relay. Each request declares a body
 * length and withholds the bytes, so it keeps its general permit until the test releases it. The
 * probe's first upgrade must again be refused {@code 503}.</li>
 * </ul>
 * Each control then releases what it held and runs the same probe again, which must pass: that
 * recovery is the matched positive control proving the held permits, and nothing else, made the probe
 * fail. The release runs in a {@code finally} block, so a failing control does not starve the tests
 * that follow it on the same instance.
 * <p>
 * <strong>What the controls do not prove.</strong> They prove that the probe observes an exhausted pool
 * on either budget. They do not replay a defect in the relay's own release path in production code: a
 * held permit is held by the test fixture, not leaked by the gateway. The unit-level relay tests in the
 * {@code api-sheriff} module own that path.
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
 * <p>
 * <strong>Early first frame.</strong> {@link #firstFrameSentOnOpenEchoesOnEveryUpgrade()} measures the
 * relay's <em>client leg</em> against the production gateway: across {@link #EARLY_FRAME_UPGRADES}
 * sequential upgrades, the client sends its first text frame from {@code WebSocket.Listener.onOpen} —
 * with no gap after the {@code 101} — and asserts that exact frame echoes back. A frame that reaches the
 * gateway's client leg before the relay has installed its frame handler is dispatched to no handler and
 * never echoes, so a loss surfaces as that upgrade's echo timing out. A run in which every upgrade
 * echoes is a zero-reproduction result over those upgrades, not proof that the window cannot open. The
 * relay's <em>upstream leg</em> is not observable here: the go-httpbin {@code /websocket/echo} upstream
 * never speaks first, so no upstream frame can reach the gateway before the client has sent one. That
 * leg's verdict rests on the deterministic reproduction in the {@code api-sheriff} module's
 * {@code WebSocketRelayStageTest}.
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

    /** The status the edge refuses an upgrade with when an admission budget is exhausted. */
    private static final int SERVICE_UNAVAILABLE = 503;

    /** The public route a held request is sent to: a POST route on the shared endpoints directory. */
    private static final String HELD_REQUEST_PATH = "/upload/small";

    /**
     * The general admission budget of the low-cap instance ({@code edge_hardening.admission_cap}),
     * and the number of in-flight requests the general-budget negative control holds. Stated here,
     * not read from the overlay: a bound read from the configuration under test would hold by
     * construction.
     * <p>
     * Package-private because {@code WsAdmissionActivationWiringTest} reads it: its
     * {@code overlayCapsEqualTheHeldCountsOfTheNegativeControls} guard requires the overlay to declare
     * exactly this value, so a drift of the overlay fails the fast surefire guard.
     */
    static final int LOW_CAP_ADMISSION_CAP = 8;

    /**
     * The relay sub-budget of the low-cap instance ({@code edge_hardening.websocket_relay_cap}), and the
     * number of established relays the relay negative control holds. Stated here for the same reason
     * as {@link #LOW_CAP_ADMISSION_CAP}, and read by the same surefire guard.
     */
    static final int LOW_CAP_RELAY_CAP = 2;

    /**
     * Sequential upgrades driven against the low-cap instance. It exceeds BOTH caps that instance
     * declares ({@link #LOW_CAP_ADMISSION_CAP}, {@link #LOW_CAP_RELAY_CAP}), so a leak of either permit
     * fails the run: the relay sub-budget would run dry on the 3rd upgrade and the general budget on
     * the 9th. With both permits returned at relay teardown, all ten succeed.
     * <p>
     * Package-private because {@code WsAdmissionActivationWiringTest} reads it: its
     * {@code overlayDeclaresAnExhaustibleAdmissionBudget} guard bounds both declared caps by this
     * value rather than by a copy of it, so lowering the upgrade count below a cap fails that guard.
     */
    static final int LOW_CAP_SEQUENTIAL_UPGRADES = 10;

    /**
     * Sequential upgrades the early-frame test drives against the primary instance, each sending its
     * first frame from {@code onOpen}. This is the measurement's sample size: a zero-loss run is
     * reported with this N, never as an all-clear.
     */
    private static final int EARLY_FRAME_UPGRADES = 50;

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
    @DisplayName("a tokenless handshake on a bearer WS route is refused as the unrouted 404 before any upstream dial")
    void unauthenticatedBearerHandshakeRejected() {
        WebSocketHandshakeException failure = expectHandshakeFailure("/ws/bearer", ALLOWED_ORIGIN);
        assertEquals(UniformNotFoundAssertions.UNROUTED_STATUS, failure.getResponse().statusCode(),
                "a bearer WS route must refuse a missing token at the auth stage, before the Origin gate and dial, "
                        + "answered as the unrouted 404 so the handshake cannot tell the route exists");
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
        runLowCapProbe();
    }

    @Test
    @DisplayName("with the relay sub-budget held, the exhaustion probe fails; once released, it passes")
    void probeFailsWhileTheRelaySubBudgetIsHeldAndPassesOnceReleased() throws Exception {
        // Arrange — hold the whole relay sub-budget with established, echo-confirmed relays.
        List<Held> held = new ArrayList<>();
        try {
            for (int relay = 1; relay <= LOW_CAP_RELAY_CAP; relay++) {
                held.add(holdLowCapRelay(relay));
            }

            // Act
            AssertionError failure = assertThrows(AssertionError.class, WebSocketProxyIT::runLowCapProbe,
                    "with " + LOW_CAP_RELAY_CAP + " relays held, the probe must observe the exhausted relay sub-budget");

            // Assert
            assertFirstUpgradeRefusedForCapacity(failure);
        } finally {
            releaseAll(held);
        }

        // Assert — the matched positive control: the same probe passes once the held relays closed.
        runLowCapProbe();
    }

    @Test
    @DisplayName("with the general admission budget held by in-flight requests, the exhaustion probe fails; once released, it passes")
    void probeFailsWhileTheGeneralAdmissionBudgetIsHeldAndPassesOnceReleased() throws Exception {
        // Arrange — hold the whole general budget with in-flight requests whose bodies are withheld.
        List<HeldRequest> held = new ArrayList<>();
        try {
            for (int request = 1; request <= LOW_CAP_ADMISSION_CAP; request++) {
                held.add(holdLowCapRequest(request));
            }
            for (HeldRequest request : held) {
                request.assertStillInFlight("before the probe ran");
            }

            // Act
            AssertionError failure = assertThrows(AssertionError.class, WebSocketProxyIT::runLowCapProbe,
                    "with " + LOW_CAP_ADMISSION_CAP + " requests held, the probe must observe the exhausted general budget");

            // Assert — refused at the first upgrade, while every held request still held its permit.
            assertFirstUpgradeRefusedForCapacity(failure);
            for (HeldRequest request : held) {
                request.assertStillInFlight("while the probe ran");
            }
        } finally {
            releaseAll(held);
        }

        // Assert — the matched positive control: the same probe passes once the held requests ended.
        runLowCapProbe();
    }

    /**
     * The exhaustion probe: {@link #LOW_CAP_SEQUENTIAL_UPGRADES} sequential open / echo / close relays
     * against the low-cap instance, then an ordinary HTTP request that must answer {@code 200}. A relay
     * the gateway refuses fails the probe with the {@link AssertionError} {@link #openLowCapRelay}
     * raises, which names the refused upgrade.
     */
    private static void runLowCapProbe() throws Exception {
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

    @Test
    @DisplayName("a first frame sent from onOpen, with no gap after the 101, echoes back on every upgrade")
    void firstFrameSentOnOpenEchoesOnEveryUpgrade() throws Exception {
        for (int upgrade = 1; upgrade <= EARLY_FRAME_UPGRADES; upgrade++) {
            String payload = "sheriff-ws-early-frame-" + upgrade;
            var listener = new SendOnOpenListener(payload);
            WebSocket socket = httpClient.newWebSocketBuilder()
                    .header("Origin", ALLOWED_ORIGIN)
                    .buildAsync(URI.create(wsBaseUri + "/ws/echo"), listener)
                    .get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            try {
                listener.sent.get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                String echoed = awaitEarlyEcho(listener, upgrade);
                assertEquals(payload, echoed,
                        "upgrade " + upgrade + " must echo the first frame it sent from onOpen verbatim");
            } finally {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            }
        }
    }

    /**
     * Awaits the echo of an early first frame, translating a timeout into an assertion failure that
     * names the upgrade and the frame-loss reading — a bare {@link TimeoutException} would say only
     * that time ran out.
     */
    private static String awaitEarlyEcho(SendOnOpenListener listener, int upgrade) throws Exception {
        try {
            return listener.recorder.firstMessage.get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException lost) {
            throw new AssertionError("upgrade " + upgrade + " of " + EARLY_FRAME_UPGRADES
                    + ": the first frame sent from onOpen never echoed — the gateway dropped a frame that"
                    + " reached its client leg before the relay was wired", lost);
        }
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
     * Asserts that the probe failed where an exhausted budget makes it fail: at its first upgrade, which
     * the edge refused {@code 503}. A failure anywhere else — a later upgrade, a lost echo, another
     * status — is not the observation the negative control exists to make.
     */
    private static void assertFirstUpgradeRefusedForCapacity(AssertionError failure) {
        String firstUpgradeRefused = "upgrade 1 of " + LOW_CAP_SEQUENTIAL_UPGRADES + " was refused";
        assertTrue(String.valueOf(failure.getMessage()).startsWith(firstUpgradeRefused),
                "the probe must fail at its first upgrade, was: " + failure.getMessage());
        WebSocketHandshakeException refused = assertInstanceOf(WebSocketHandshakeException.class, failure.getCause(),
                "the first upgrade must have been refused by the gateway with a non-101 answer");
        assertEquals(SERVICE_UNAVAILABLE, refused.getResponse().statusCode(),
                "an exhausted admission budget refuses the upgrade 503");
    }

    /**
     * Opens one relay on the low-cap instance and confirms it end to end with an echo, so the relay
     * holds both of its permits before the probe runs.
     */
    private static HeldRelay holdLowCapRelay(int relay) throws Exception {
        var listener = new RecordingListener();
        WebSocket socket;
        try {
            socket = httpClient.newWebSocketBuilder()
                    .header("Origin", ALLOWED_ORIGIN)
                    .buildAsync(URI.create(lowCapWsBaseUri + "/ws/echo"), listener)
                    .get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException refused) {
            throw new AssertionError("fixture: held relay " + relay + " of " + LOW_CAP_RELAY_CAP
                    + " could not be established on an idle low-cap instance", refused.getCause());
        }
        String payload = "sheriff-ws-held-" + relay;
        try {
            socket.sendText(payload, true).get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(payload, listener.firstMessage.get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "fixture: held relay " + relay + " must round-trip its frame before the probe runs");
        } catch (InterruptedException | ExecutionException | TimeoutException | AssertionError notEstablished) {
            socket.abort();
            throw notEstablished;
        }
        return new HeldRelay(socket, listener);
    }

    /**
     * Sends one {@code POST} to the low-cap instance whose body is declared but withheld, so the request
     * stays in flight — and keeps its general admission permit — until {@link HeldRequest#release()}.
     * HTTP/1.1 puts every held request on a connection of its own. The method returns once the client
     * has subscribed to the body, which it does after the request headers are written.
     */
    private static HeldRequest holdLowCapRequest(int index) throws Exception {
        var body = new WithheldBodyPublisher();
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://localhost:" + lowCapHttpPort + HELD_REQUEST_PATH))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "text/plain")
                .POST(body)
                .build();
        CompletableFuture<HttpResponse<String>> response =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var held = new HeldRequest(index, body, response);
        try {
            CompletableFuture.anyOf(body.subscribed, response).get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException notSent) {
            var fixtureFailure = new AssertionError("fixture: held request " + index + " of " + LOW_CAP_ADMISSION_CAP
                    + " never started sending its body", notSent);
            try {
                held.release();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fixtureFailure.addSuppressed(interrupted);
            } catch (ExecutionException | TimeoutException releaseFailed) {
                fixtureFailure.addSuppressed(releaseFailed);
            }
            throw fixtureFailure;
        }
        held.assertStillInFlight("as soon as it was sent");
        return held;
    }

    /**
     * Releases every held permit, attempting each one even when an earlier release fails. Any failed
     * release fails the fixture with an {@link AssertionError} carrying the first failure as its cause
     * and the later ones suppressed. An interrupt is restored on the thread before it is reported; the
     * remaining permits are still released, because each release frees its permit before it waits.
     */
    private static void releaseAll(List<? extends Held> held) {
        AssertionError failure = null;
        for (Held permit : held) {
            try {
                permit.release();
            } catch (InterruptedException | ExecutionException | TimeoutException releaseFailed) {
                if (releaseFailed instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (failure == null) {
                    failure = new AssertionError("fixture: a held admission permit could not be released",
                            releaseFailed);
                } else {
                    failure.addSuppressed(releaseFailed);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** An admission permit a negative control holds from a test fixture until it releases it. */
    private interface Held {

        /** Releases the permit and waits until the gateway has ended the operation that held it. */
        void release() throws InterruptedException, ExecutionException, TimeoutException;
    }

    /** An established relay holding a general permit and a relay sub-permit. */
    private record HeldRelay(WebSocket socket, RecordingListener listener) implements Held {

        /**
         * Closes the relay and awaits the close frame. The relay's teardown releases both permits
         * before it closes either leg, so the observed close frame proves the release has happened. A
         * relay that cannot be closed cleanly is aborted, which tears it down as well.
         */
        @Override
        public void release() throws InterruptedException, ExecutionException, TimeoutException {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "released").get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                listener.closed.get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException closeFailed) {
                socket.abort();
                throw closeFailed;
            }
        }
    }

    /** An in-flight request holding a general permit while its body is withheld. */
    private record HeldRequest(int index, WithheldBodyPublisher body,
    CompletableFuture<HttpResponse<String>> response) implements Held {

        /**
         * Fails the control with a fixture message when the held request has already ended — answered
         * or failed. A request that ended returned its permit, so the probe would not observe an
         * exhausted budget, and the probe's verdict would say nothing about it.
         */
        void assertStillInFlight(String when) {
            if (response.isDone()) {
                String outcome = response.handle((answer, failure) -> failure == null
                        ? "answered " + answer.statusCode()
                        : "failed with " + failure).join();
                throw new AssertionError("fixture: held request " + index + " of " + LOW_CAP_ADMISSION_CAP
                        + " was no longer in flight " + when + " (" + outcome + ") — it no longer held its permit");
            }
        }

        /**
         * Sends the withheld body and waits until the request has ended, answered or failed. Either way
         * the gateway has ended the request and returned its permit.
         */
        @Override
        public void release() throws InterruptedException, ExecutionException, TimeoutException {
            body.release();
            response.handle((answer, failure) -> null).get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * A body publisher that declares a small body and produces it only once {@link #release()} is
     * called. Until then the request is in flight with its body pending. {@link #subscribed} completes
     * when the client subscribes to the body, which it does after writing the request headers.
     */
    private static final class WithheldBodyPublisher implements HttpRequest.BodyPublisher {

        private static final byte[] BODY = "sheriff-held-request".getBytes(StandardCharsets.UTF_8);

        private final CompletableFuture<Void> subscribed = new CompletableFuture<>();
        private final CompletableFuture<Void> released = new CompletableFuture<>();

        @Override
        public long contentLength() {
            return BODY.length;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            AtomicBoolean ended = new AtomicBoolean();
            subscriber.onSubscribe(new Flow.Subscription() {

                @Override
                public void request(long n) {
                    if (n <= 0) {
                        if (ended.compareAndSet(false, true)) {
                            subscriber.onError(new IllegalArgumentException("non-positive subscription request: " + n));
                        }
                        return;
                    }
                    released.thenRun(() -> {
                        if (ended.compareAndSet(false, true)) {
                            subscriber.onNext(ByteBuffer.wrap(BODY));
                            subscriber.onComplete();
                        }
                    });
                }

                @Override
                public void cancel() {
                    ended.set(true);
                }
            });
            subscribed.complete(null);
        }

        void release() {
            released.complete(null);
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
     * Sends one text frame from {@link #onOpen(WebSocket)} — the earliest point the JDK client allows,
     * with no gap after the {@code 101} — and records everything received through a
     * {@link RecordingListener}.
     */
    private static final class SendOnOpenListener implements WebSocket.Listener {

        private final String payload;
        private final RecordingListener recorder = new RecordingListener();
        private final CompletableFuture<WebSocket> sent = new CompletableFuture<>();

        SendOnOpenListener(String payload) {
            this.payload = payload;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            webSocket.sendText(payload, true).whenComplete((socket, failure) -> {
                if (failure != null) {
                    sent.completeExceptionally(failure);
                } else {
                    sent.complete(socket);
                }
            });
        }

        @Override
        public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            return recorder.onText(webSocket, data, last);
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            return recorder.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            sent.completeExceptionally(error);
            recorder.onError(webSocket, error);
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
