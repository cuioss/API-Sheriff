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
package de.cuioss.sheriff.gateway.edge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;

import de.cuioss.sheriff.gateway.bff.cookie.CookieSessionBinding;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.cookie.SessionActivityCookieCodec;
import de.cuioss.sheriff.gateway.bff.reserved.ClientJwksEndpoint;
import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.gateway.bff.session.SessionRelayRegistry;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EdgeHardeningConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedRoute;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.portal.PortalEndpoint;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.EgressTrustProfiles;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.sheriff.gateway.testsupport.UnreachablePort;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.Router;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A WebSocket relay the edge opens for a request a session was let through with does not outlive that
 * session. Every case drives a real upgrade through {@link GatewayEdgeRoute} against a stub echo
 * upstream, so the session stage, the tracking in the dispatch, the relay and the release of the
 * tracking entry are the production ones.
 * <p>
 * The server-mode fixture wires the store's end listener to the relay registry by hand, the way the
 * session runtime's producer does; that the producer does so is proven with the producer.
 */
@EnableGeneratorController
@DisplayName("GatewayEdgeRoute — a WebSocket relay opened with a session ends with that session")
class GatewayEdgeSessionRelayTest {

    private static final String OIDC_HOST = "gw.example.com";
    private static final String ORIGIN = "https://gw.example.com";
    private static final String LOGOUT_PATH = "/auth/logout";
    private static final short SESSION_ENDED_CODE = 1008;
    private static final String SESSION_ENDED_REASON = "session ended";
    private static final Duration SESSION_TTL = Duration.ofHours(1);
    /** Longer than any fixture session lives, so no idle deadline is reached unless a case moves the clock. */
    private static final Duration IDLE_TIMEOUT = Duration.ofHours(8);
    private static final String SESSION_WS = "/ws-session";
    private static final String OPEN_WS = "/ws-open";
    private static final String DEAD_WS = "/ws-dead";
    private static final String SECOND_UPSTREAM_WS = "/ws-second";

    private final AtomicInteger upstreamConnects = new AtomicInteger();
    private final List<Closed> upstreamCloses = new CopyOnWriteArrayList<>();

    private Vertx vertx;
    private ExecutorService virtualThreadExecutor;
    private HttpServer echoUpstream;
    private @Nullable HttpServer front;
    private WebSocketClient wsClient;
    private HttpClient httpClient;
    private SessionRelayRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        echoUpstream = Awaits.connect(vertx.createHttpServer().webSocketHandler(ws -> {
            upstreamConnects.incrementAndGet();
            ws.textMessageHandler(ws::writeTextMessage);
            ws.closeHandler(v -> upstreamCloses.add(new Closed(ws.closeStatusCode(), ws.closeReason())));
        }).listen(0, LoopbackHost.ADDRESS), "the stub echo upstream to start listening");
        wsClient = vertx.createWebSocketClient();
        httpClient = vertx.createHttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        Awaits.teardown(wsClient.close(), "the WebSocket client to close");
        Awaits.teardown(httpClient.close(), "the HTTP client to close");
        if (front != null) {
            Awaits.teardown(front.close(), "the edge front server to close");
        }
        Awaits.teardown(echoUpstream.close(), "the stub echo upstream to close");
        virtualThreadExecutor.close();
        Awaits.teardown(vertx.close(), "Vert.x to close");
    }

    @Nested
    @DisplayName("server mode — the store reports the end and the relay is closed with 1008")
    class ServerMode {

        private InMemorySessionStore store;
        private SessionBinding binding;

        private void startEdge(int relayRegistryCapacity, int sessionsPerSubject, EdgeHardeningOptions hardening,
                int secondUpstreamPort) throws Exception {
            registry = new SessionRelayRegistry(relayRegistryCapacity, Clock.systemUTC());
            store = new InMemorySessionStore(16, IDLE_TIMEOUT, sessionsPerSubject, registry);
            binding = GatewayEdgeRouteBffWiringTest.serverBinding(store);
            BffRuntime runtime = GatewayEdgeRouteBffWiringTest.activeRuntime(binding, ClientJwksEndpoint.withheld(),
                    registry, store::isHeld);
            startFront(runtime, serverModeOidc(), hardening, secondUpstreamPort);
        }

        private void startEdge() throws Exception {
            startEdge(16, Integer.MAX_VALUE, new EdgeHardeningOptions(), echoUpstream.actualPort());
        }

        private Session login(String sub, @Nullable String sid) {
            SessionRecord session = SessionRecord.builder().sessionId(SessionRecord.newSessionId())
                    .accessToken(Generators.letterStrings(16, 32).next())
                    .idToken(Generators.letterStrings(16, 32).next())
                    .sub(sub).sid(sid).expiresAt(Instant.now().plus(SESSION_TTL)).build();
            return new Session(session, requestCookie(binding.bind(session, Instant.now()).setCookieHeaders()));
        }

        private Session login() {
            return login(Generators.letterStrings(8, 16).next(), Generators.letterStrings(8, 16).next());
        }

        @Test
        @DisplayName("an RP-initiated logout through the edge closes the session's relay on both legs")
        void logoutClosesTheRelay() throws Exception {
            startEdge();
            Session session = login();
            RelayClient relay = openRelay(SESSION_WS, session.cookie());

            int logoutStatus = logout(session.cookie());

            assertEquals(302, logoutStatus, "precondition: the logout was answered");
            assertClosedBecauseTheSessionEnded(relay);
        }

        @Test
        @DisplayName("a destroy by the provider's session id — a back-channel logout naming sid — closes the relay")
        void destroyBySidClosesTheRelay() throws Exception {
            startEdge();
            Session session = login();
            RelayClient relay = openRelay(SESSION_WS, session.cookie());

            int destroyed = store.destroyBySid(session.record().sid());

            assertEquals(1, destroyed, "precondition: the store ended exactly this session");
            assertClosedBecauseTheSessionEnded(relay);
        }

        @Test
        @DisplayName("a destroy by subject — a back-channel logout naming only sub — closes every relay of the subject")
        void destroyBySubClosesEveryRelayOfTheSubject() throws Exception {
            startEdge();
            String sub = Generators.letterStrings(8, 16).next();
            Session first = login(sub, null);
            Session second = login(sub, null);
            Session other = login();
            RelayClient firstRelay = openRelay(SESSION_WS, first.cookie());
            RelayClient secondRelay = openRelay(SESSION_WS, second.cookie());
            RelayClient otherRelay = openRelay(SESSION_WS, other.cookie());

            int destroyed = store.destroyBySub(sub);

            assertEquals(2, destroyed, "precondition: the store ended both sessions of the subject");
            assertEquals(sessionEnded(), Awaits.connect(firstRelay.closed, "the first relay to be closed"));
            assertEquals(sessionEnded(), Awaits.connect(secondRelay.closed, "the second relay to be closed"));
            assertStillRelaying(otherRelay, 1);
        }

        @Test
        @DisplayName("a destroy by session identity — what a login does to the session the browser presented — closes the relay")
        void destroyByIdClosesTheRelay() throws Exception {
            startEdge();
            Session session = login();
            RelayClient relay = openRelay(SESSION_WS, session.cookie());

            store.destroyById(session.record().sessionId());

            assertClosedBecauseTheSessionEnded(relay);
        }

        @Test
        @DisplayName("the sweep removing an expired session closes the relay")
        void sweepClosesTheRelay() throws Exception {
            startEdge();
            Session session = login();
            RelayClient relay = openRelay(SESSION_WS, session.cookie());

            int swept = store.sweepExpired(Instant.now().plus(IDLE_TIMEOUT).plusSeconds(1));

            assertEquals(1, swept, "precondition: the sweep removed the session");
            assertClosedBecauseTheSessionEnded(relay);
        }

        @Test
        @DisplayName("a login beyond the per-subject bound closes the relay of the session it ends")
        void perSubjectEvictionClosesTheRelay() throws Exception {
            startEdge(16, 1, new EdgeHardeningOptions(), echoUpstream.actualPort());
            String sub = Generators.letterStrings(8, 16).next();
            Session oldest = login(sub, null);
            RelayClient relay = openRelay(SESSION_WS, oldest.cookie());

            Session newest = login(sub, null);

            assertClosedBecauseTheSessionEnded(relay);
            assertTrue(store.isHeld(newest.record().sessionId()), "the new session is the one that is held");
        }

        @Test
        @DisplayName("a re-issued cookie handle closes nothing: the relay keeps relaying")
        void handleReissueLeavesTheRelayOpen() throws Exception {
            startEdge();
            Session session = login();
            RelayClient relay = openRelay(SESSION_WS, session.cookie());

            boolean reissued = store.replaceAndReissueHandle(session.record(),
                    "handle-" + SessionRecord.newSessionId());

            assertTrue(reissued, "precondition: the store re-issued the handle of the live session");
            assertStillRelaying(relay, 1);
        }

        @Test
        @DisplayName("a refresh replacing the record closes nothing")
        void refreshLeavesTheRelayOpen() throws Exception {
            startEdge();
            Session session = login();
            RelayClient relay = openRelay(SESSION_WS, session.cookie());

            boolean replaced = store.replaceIfPresent(session.record());

            assertTrue(replaced, "precondition: the store replaced the record of the live session");
            assertStillRelaying(relay, 1);
        }

        @Test
        @DisplayName("a relay on a route without a session is not tracked")
        void relayWithoutSessionIsNotTracked() throws Exception {
            startEdge();

            RelayClient relay = openRelay(OPEN_WS, null);

            assertEquals(0, registry.size(), "nothing is tracked for a route that admits without a session");
            assertFalse(relay.closed.isDone(), "and the relay is open");
        }

        @Test
        @DisplayName("refuses the upgrade 503 before dialing when the relay registry is at its bound")
        void refusesUpgradeAtTheRegistryBound() throws Exception {
            // Two general permits: the open relay holds one, so a refused upgrade that kept its permit
            // would leave none for the plain request below.
            startEdge(1, Integer.MAX_VALUE, new EdgeHardeningOptions(new EdgeHardeningConfig(2, 2)),
                    echoUpstream.actualPort());
            Session session = login();
            RelayClient held = openRelay(SESSION_WS, session.cookie());
            int connectsBefore = upstreamConnects.get();

            for (int attempt = 0; attempt < 3; attempt++) {
                assertEquals(503, rejectedUpgradeStatus(SESSION_WS, session.cookie()),
                        "an upgrade the registry cannot take is refused");
            }

            assertAll("the refusal opened nothing and kept nothing",
                    () -> assertEquals(connectsBefore, upstreamConnects.get(), "the upstream was not dialed"),
                    () -> assertEquals(1, registry.size(), "only the relay that was already open is tracked"),
                    () -> assertEquals(404, plainRequestStatus("/no-such-route"),
                            "the admission permit of every refused upgrade was returned"));
            assertStillRelaying(held, 1);
        }

        @Test
        @DisplayName("stops tracking a relay the relay sub-budget refuses")
        void releasesTrackingWhenTheRelayBudgetRefuses() throws Exception {
            startEdge(16, Integer.MAX_VALUE, new EdgeHardeningOptions(new EdgeHardeningConfig(8, 1)),
                    echoUpstream.actualPort());
            Session session = login();
            openRelay(SESSION_WS, session.cookie());

            int status = rejectedUpgradeStatus(SESSION_WS, session.cookie());

            assertEquals(503, status, "the second relay is beyond the sub-budget of one");
            assertEquals(1, registry.size(), "the refused relay's tracking entry is gone");
        }

        @Test
        @DisplayName("stops tracking a relay whose upstream cannot be dialed")
        void releasesTrackingWhenTheUpstreamDialFails() throws Exception {
            startEdge();
            Session session = login();

            int status = rejectedUpgradeStatus(DEAD_WS, session.cookie());

            assertEquals(502, status, "an unreachable upstream is answered before the upgrade");
            awaitTracked(0, "the tracking entry of the failed dial to be released");
        }

        @Test
        @DisplayName("stops tracking a relay whose upstream refuses the upgrade")
        void releasesTrackingWhenTheUpstreamRefusesTheUpgrade() throws Exception {
            HttpServer refusing = Awaits.connect(vertx.createHttpServer()
                    .requestHandler(request -> request.response().setStatusCode(403).end())
                    .listen(0, LoopbackHost.ADDRESS), "the refusing upstream to start listening");
            try {
                startEdge(16, Integer.MAX_VALUE, new EdgeHardeningOptions(), refusing.actualPort());
                Session session = login();

                int status = rejectedUpgradeStatus(SECOND_UPSTREAM_WS, session.cookie());

                assertTrue(status >= 400, "the upgrade was not accepted: " + status);
                awaitTracked(0, "the tracking entry of the refused upgrade to be released");
            } finally {
                Awaits.teardown(refusing.close(), "the refusing upstream to close");
            }
        }

        /**
         * Two upgrades wait on a dial the upstream never answers, and only one client leaves. The client
         * that stays is a matched control: its dial is the older of the two, so anything that ended a
         * pending dial for a reason other than its client leaving would reach that one no later than the
         * other. One entry leaving while the other stays is therefore the client's disconnect and nothing
         * else, and the two probes that follow say which session the remaining entry belongs to.
         */
        @Test
        @DisplayName("stops tracking a relay whose client leaves while the upstream is being dialed, and only that relay")
        void releasesTrackingWhenTheClientLeavesDuringTheDial() throws Exception {
            AtomicInteger handshakes = new AtomicInteger();
            // Receives each relay's handshake and never answers it, so every dial stays pending.
            HttpServer holding = Awaits.connect(vertx.createHttpServer()
                    .requestHandler(request -> handshakes.incrementAndGet())
                    .listen(0, LoopbackHost.ADDRESS), "the holding upstream to start listening");
            NetClient netClient = vertx.createNetClient();
            try {
                startEdge(16, Integer.MAX_VALUE, new EdgeHardeningOptions(), holding.actualPort());
                Session waiting = login();
                Session leaving = login();
                sendUpgrade(netClient, waiting);
                awaitPendingDials(handshakes, 1);
                NetSocket leavingClient = sendUpgrade(netClient, leaving);
                awaitPendingDials(handshakes, 2);

                Awaits.connect(leavingClient.close(), "the client to drop its connection");

                awaitTracked(1, "the tracking entry of the abandoned upgrade to be released");
                registry.sessionEnded(leaving.record().sessionId());
                assertEquals(1, registry.size(),
                        "the entry that left is the one of the client that left: its session has none to end");
                registry.sessionEnded(waiting.record().sessionId());
                assertEquals(0, registry.size(),
                        "the entry that stayed is the one of the client still waiting on its dial");
            } finally {
                Awaits.teardown(netClient.close(), "the raw TCP client to close");
                Awaits.teardown(holding.close(), "the holding upstream to close");
            }
        }

        /** Sends {@code session}'s upgrade over a raw connection, which its caller can drop mid-dial. */
        private NetSocket sendUpgrade(NetClient netClient, Session session) throws Exception {
            NetSocket socket = Awaits.connect(
                    netClient.connect(requireFront().actualPort(), LoopbackHost.ADDRESS),
                    "the TCP connection to the edge");
            socket.write(String.join("\r\n",
                    "GET " + SECOND_UPSTREAM_WS + "/room HTTP/1.1",
                    "Host: " + LoopbackHost.ADDRESS + ":" + requireFront().actualPort(),
                    "Upgrade: websocket",
                    "Connection: Upgrade",
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==",
                    "Sec-WebSocket-Version: 13",
                    "Origin: " + ORIGIN,
                    "Cookie: " + session.cookie(),
                    "", ""));
            return socket;
        }

        /**
         * Waits until the holding upstream has received {@code expected} handshakes, then asserts that
         * exactly that many dials are pending and that each of them is tracked.
         */
        private void awaitPendingDials(AtomicInteger handshakes, int expected) throws Exception {
            Awaits.until(() -> handshakes.get() >= expected, "the upstream to receive the relay's handshake",
                    Awaits.CONNECT_CEILING_SECONDS);
            assertEquals(expected, handshakes.get(), "precondition: the upstream received each relay's handshake once");
            assertEquals(expected, registry.size(),
                    "precondition: each relay is tracked before its upstream answers");
        }
    }

    @Nested
    @DisplayName("cookie mode — nothing server-side observes a logout; the relay ends at the absolute expiry")
    class CookieMode {

        private CookieSessionBinding binding;

        private void startEdge(Duration sessionTtl) throws Exception {
            byte[] key = new byte[32];
            Arrays.fill(key, (byte) 0x11);
            byte[] salt = new byte[32];
            Arrays.fill(salt, (byte) 0x22);
            byte[] activityKey = new byte[32];
            Arrays.fill(activityKey, (byte) 0x44);
            binding = new CookieSessionBinding(
                    new SealedSessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, sessionTtl,
                            SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET, new SecretKeySpec(key, "AES"),
                            (byte) 1),
                    salt,
                    new SessionActivityCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME,
                            new SecretKeySpec(activityKey, "AES"), (byte) 2),
                    Duration.ofMinutes(30));
            registry = new SessionRelayRegistry(16, Clock.systemUTC());
            BffRuntime runtime = GatewayEdgeRouteBffWiringTest.activeRuntime(binding, ClientJwksEndpoint.withheld(),
                    registry, sessionId -> true);
            OidcConfig oidc = OidcConfig.builder()
                    .redirectUri(ORIGIN + "/auth/callback")
                    .logout(OidcConfig.Logout.builder().path(LOGOUT_PATH)
                            .postLogoutRedirectUri(ORIGIN + "/auth/logout/return").build())
                    .session(OidcConfig.Session.builder().mode(OidcConfig.Session.MODE_COOKIE).build())
                    .build();
            startFront(runtime, oidc, new EdgeHardeningOptions(), echoUpstream.actualPort());
        }

        private String login(Duration sessionTtl) {
            Instant login = Instant.now();
            return requestCookie(binding.bind(SessionRecord.builder().sessionId(SessionRecord.newSessionId())
                    .accessToken(Generators.letterStrings(16, 32).next())
                    .idToken(Generators.letterStrings(16, 32).next())
                    .sub(Generators.letterStrings(8, 16).next())
                    .expiresAt(login.plus(sessionTtl)).build(), login).setCookieHeaders());
        }

        @Test
        @DisplayName("a logout through the edge leaves the relay open")
        void logoutLeavesTheRelayOpen() throws Exception {
            startEdge(SESSION_TTL);
            String cookie = login(SESSION_TTL);
            RelayClient relay = openRelay(SESSION_WS, cookie);

            int logoutStatus = logout(cookie);

            assertEquals(302, logoutStatus, "precondition: the logout was answered");
            assertStillRelaying(relay, 1);
        }

        @Test
        @DisplayName("the relay is closed on both legs with 1008 when the session's absolute expiry is reached")
        void absoluteExpiryClosesTheRelay() throws Exception {
            Duration shortLife = Duration.ofSeconds(4);
            startEdge(shortLife);
            RelayClient relay = openRelay(SESSION_WS, login(shortLife));

            assertClosedBecauseTheSessionEnded(relay);
        }
    }

    @Nested
    @DisplayName("BffRuntime.trackSessionRelay — the session is looked up again after the relay is tracked")
    class TrackSessionRelay {

        private BffRuntime runtimeHolding(SessionRelayRegistry relays, boolean sessionHeld) {
            return GatewayEdgeRouteBffWiringTest.activeRuntime(
                    GatewayEdgeRouteBffWiringTest.serverBinding(GatewayEdgeRouteBffWiringTest.newStore()),
                    ClientJwksEndpoint.withheld(), relays, sessionId -> sessionHeld);
        }

        @Test
        @DisplayName("hands back a handle that has already ended when the session was destroyed before the call")
        void endsHandleOfSessionDestroyedBeforeTracking() {
            SessionRelayRegistry relays = new SessionRelayRegistry(4, Clock.systemUTC());
            AtomicInteger closes = new AtomicInteger();

            Optional<SessionRelayRegistry.Tracked> tracked = runtimeHolding(relays, false)
                    .trackSessionRelay(SessionRecord.newSessionId(), Instant.now().plus(SESSION_TTL));
            tracked.orElseThrow().onSessionEnd(closes::incrementAndGet);

            assertAll("a relay tracked for a session that is gone is told to close at once",
                    () -> assertTrue(tracked.orElseThrow().sessionEnded()),
                    () -> assertEquals(1, closes.get(), "its close action ran when it was registered"),
                    () -> assertEquals(0, relays.size(), "and it is no longer tracked"));
        }

        @Test
        @DisplayName("control: a session that is still held leaves the handle live and tracked")
        void keepsHandleOfHeldSession() {
            SessionRelayRegistry relays = new SessionRelayRegistry(4, Clock.systemUTC());
            AtomicInteger closes = new AtomicInteger();

            Optional<SessionRelayRegistry.Tracked> tracked = runtimeHolding(relays, true)
                    .trackSessionRelay(SessionRecord.newSessionId(), Instant.now().plus(SESSION_TTL));
            tracked.orElseThrow().onSessionEnd(closes::incrementAndGet);

            assertAll("nothing ended",
                    () -> assertFalse(tracked.orElseThrow().sessionEnded()),
                    () -> assertEquals(0, closes.get()),
                    () -> assertEquals(1, relays.size()));
        }

        @Test
        @DisplayName("hands back nothing when the registry is at its bound")
        void refusesAtTheBound() {
            SessionRelayRegistry relays = new SessionRelayRegistry(1, Clock.systemUTC());
            BffRuntime runtime = runtimeHolding(relays, true);
            runtime.trackSessionRelay(SessionRecord.newSessionId(), Instant.now().plus(SESSION_TTL));

            Optional<SessionRelayRegistry.Tracked> beyond = runtime.trackSessionRelay(SessionRecord.newSessionId(),
                    Instant.now().plus(SESSION_TTL));

            assertEquals(Optional.empty(), beyond);
            assertEquals(1, relays.size());
        }

        @Test
        @DisplayName("the inert runtime tracks nothing and says so")
        void inertRuntimeRefuses() {
            BffRuntime inert = BffRuntime.inert();
            String sessionId = SessionRecord.newSessionId();
            Instant expiry = Instant.now().plus(SESSION_TTL);

            assertThrows(IllegalStateException.class, () -> inert.trackSessionRelay(sessionId, expiry));
        }
    }

    private static OidcConfig serverModeOidc() {
        return OidcConfig.builder()
                .redirectUri(ORIGIN + "/auth/callback")
                .logout(OidcConfig.Logout.builder().path(LOGOUT_PATH)
                        .postLogoutRedirectUri(ORIGIN + "/auth/logout/return")
                        .backchannelPath("/auth/backchannel").build())
                .build();
    }

    /**
     * Starts the edge over {@code runtime} with a session WebSocket route and a session-less one on the
     * echo upstream, a session route on a port nothing listens on, and a session route on
     * {@code secondUpstreamPort}.
     */
    private void startFront(BffRuntime runtime, OidcConfig oidc, EdgeHardeningOptions hardening,
            int secondUpstreamPort) throws Exception {
        TokenValidator tokenValidator = TokenValidator.builder()
                .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();
        RouteTable table = new RouteTable(List.of(
                wsRoute("wssession", SESSION_WS, Require.SESSION, echoUpstream.actualPort()),
                wsRoute("wsopen", OPEN_WS, Require.NONE, echoUpstream.actualPort()),
                wsRoute("wsdead", DEAD_WS, Require.SESSION, UnreachablePort.pick()),
                wsRoute("wssecond", SECOND_UPSTREAM_WS, Require.SESSION, secondUpstreamPort)));
        GatewayEdgeRoute edge = new GatewayEdgeRoute(table, GatewayConfig.builder().version(1).oidc(oidc).build(),
                new SingletonInstance<>(tokenValidator), vertx, virtualThreadExecutor, hardening,
                new SheriffMetrics(new SimpleMeterRegistry()), runtime, EgressTrustProfiles.unconsulted(),
                PortalEndpoint.inert(), GatewayEdgeRouteBffWiringTest.gatewayJson());
        Router router = Router.router(vertx);
        edge.registerRoutes(router);
        front = Awaits.connect(vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                "the edge front server to start listening");
    }

    private HttpServer requireFront() {
        HttpServer started = front;
        if (started == null) {
            throw new IllegalStateException("the edge front server is not started");
        }
        return started;
    }

    private static ResolvedRoute wsRoute(String id, String pathPrefix, Require require, int upstreamPort) {
        return ResolvedRoute.builder()
                .id(id)
                .protocol(Protocol.WEBSOCKET)
                .match(MatchConfig.builder().pathPrefix(pathPrefix).build())
                .effectiveAuth(AuthConfig.builder().require(require).build())
                .effectiveAllowedMethods(List.of(HttpMethod.GET))
                .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamPort, ""))
                .effectiveAllowedOrigins(Set.of(ORIGIN))
                .effectiveForward(ForwardConfig.builder().headersAllow(List.of()).build())
                .build();
    }

    /** The {@code name=value} pair a browser sends back for the first of {@code setCookieHeaders}. */
    private static String requestCookie(List<String> setCookieHeaders) {
        String setCookie = setCookieHeaders.getFirst();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    private static Closed sessionEnded() {
        return new Closed(SESSION_ENDED_CODE, SESSION_ENDED_REASON);
    }

    private WebSocketConnectOptions upgradeTo(String pathPrefix, @Nullable String cookie) {
        WebSocketConnectOptions options = new WebSocketConnectOptions()
                .setHost(LoopbackHost.ADDRESS).setPort(requireFront().actualPort()).setURI(pathPrefix + "/room")
                .addHeader("Origin", ORIGIN);
        if (cookie != null) {
            options.addHeader("Cookie", cookie);
        }
        return options;
    }

    /** Opens a relay and sends one frame through it, so the relay is wired end to end when this returns. */
    private RelayClient openRelay(String pathPrefix, @Nullable String cookie) throws Exception {
        RelayClient relay = new RelayClient(upgradeTo(pathPrefix, cookie));
        String frame = Generators.letterStrings(4, 16).next();
        assertEquals(frame, relay.echo(frame), "precondition: the relay is open and relays");
        return relay;
    }

    /** The status an upgrade the edge does not accept is answered with. */
    private int rejectedUpgradeStatus(String pathPrefix, String cookie) {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> Awaits.connect(wsClient.connect(upgradeTo(pathPrefix, cookie)), "the upgrade to be rejected"));
        return assertInstanceOf(UpgradeRejectedException.class, failure.getCause()).getStatus();
    }

    private RequestOptions reservedHostRequest(String uri) {
        return new RequestOptions()
                .setServer(SocketAddress.inetSocketAddress(requireFront().actualPort(), LoopbackHost.ADDRESS))
                .setHost(OIDC_HOST).setPort(requireFront().actualPort())
                .setMethod(io.vertx.core.http.HttpMethod.GET).setURI(uri);
    }

    private int logout(String cookie) throws Exception {
        return Awaits.connect(httpClient.request(reservedHostRequest(LOGOUT_PATH))
                .compose(request -> request.putHeader("Cookie", cookie).send())
                .map(HttpClientResponse::statusCode), "the edge's answer to the logout");
    }

    private int plainRequestStatus(String uri) throws Exception {
        return Awaits.connect(httpClient.request(reservedHostRequest(uri))
                .compose(request -> request.send())
                .map(HttpClientResponse::statusCode), "the edge's answer to GET " + uri);
    }

    private void assertClosedBecauseTheSessionEnded(RelayClient relay) throws Exception {
        Closed clientClose = Awaits.connect(relay.closed, "the client leg to be closed");
        Awaits.until(() -> !upstreamCloses.isEmpty(), "the upstream leg to be closed",
                Awaits.CONNECT_CEILING_SECONDS);
        assertAll("both legs carry the session-end close and nothing stays tracked",
                () -> assertEquals(sessionEnded(), clientClose, "the client leg"),
                () -> assertEquals(List.of(sessionEnded()), List.copyOf(upstreamCloses), "the upstream leg"),
                () -> assertEquals(0, registry.size(), "the tracking entry is gone"));
    }

    /**
     * Waits until exactly {@code expected} relays are tracked, then asserts that count on the state the
     * wait left behind: the poll alone only says the count was reached at some instant.
     */
    private void awaitTracked(int expected, String what) throws Exception {
        Awaits.until(() -> registry.size() == expected, what, Awaits.ADMISSION_RELEASE_CEILING_SECONDS);
        assertEquals(expected, registry.size(), what);
    }

    private void assertStillRelaying(RelayClient relay, int trackedRelays) throws Exception {
        String frame = Generators.letterStrings(4, 16).next();
        assertEquals(frame, relay.echo(frame), "the relay still relays");
        assertFalse(relay.closed.isDone(), "its client leg was not closed");
        assertEquals(trackedRelays, registry.size(), "and it is still tracked");
    }

    /** A logged-in session and the cookie pair its browser presents. */
    private record Session(SessionRecord record, String cookie) {
    }

    /** How one end of a WebSocket saw it closed. */
    private record Closed(@Nullable Short code, @Nullable String reason) {
    }

    /** One client of the edge's relay: its socket, how it was closed, and its echoed frames. */
    private final class RelayClient {

        private final CompletableFuture<WebSocket> socket = new CompletableFuture<>();
        private final CompletableFuture<Closed> closed = new CompletableFuture<>();
        private final AtomicReference<@Nullable CompletableFuture<String>> next = new AtomicReference<>();

        RelayClient(WebSocketConnectOptions options) {
            wsClient.connect(options)
                    .onSuccess(opened -> {
                        opened.textMessageHandler(text -> {
                            CompletableFuture<String> awaited = next.getAndSet(null);
                            if (awaited != null) {
                                awaited.complete(text);
                            }
                        });
                        opened.closeHandler(v -> closed.complete(
                                new Closed(opened.closeStatusCode(), opened.closeReason())));
                        socket.complete(opened);
                    })
                    .onFailure(failure -> {
                        socket.completeExceptionally(failure);
                        closed.completeExceptionally(failure);
                    });
        }

        /** Sends {@code text} and returns the frame the echo upstream sent back through the relay. */
        String echo(String text) throws Exception {
            CompletableFuture<String> awaited = new CompletableFuture<>();
            next.set(awaited);
            Awaits.connect(socket, "the relay's client socket").writeTextMessage(text);
            return Awaits.connect(awaited, "the relay to echo a frame");
        }
    }

    /** Minimal {@link Instance} double resolving to one bean; the session routes here never read it. */
    private static final class SingletonInstance<T> implements Instance<T> {

        private final T value;

        SingletonInstance(T value) {
            this.value = value;
        }

        @Override
        public T get() {
            return value;
        }

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return false;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public void destroy(T instance) {
            // no-op: the test double owns no lifecycle
        }

        @Override
        public Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<T> iterator() {
            return List.of(value).iterator();
        }
    }
}
