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
package de.cuioss.sheriff.gateway.edge.admission;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;


import com.fasterxml.jackson.databind.ObjectMapper;
import de.cuioss.http.forwarded.ForwardedHeaderResolver;
import de.cuioss.http.forwarded.ForwardedResolverConfig;
import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.sheriff.gateway.bff.runtime.GatewayJson;
import de.cuioss.sheriff.gateway.config.model.EnumerationDetectionConfig;
import de.cuioss.sheriff.gateway.detection.SourceKey;
import de.cuioss.sheriff.gateway.detection.SourceKeyResolver;
import de.cuioss.sheriff.gateway.detection.SourceLedger;
import de.cuioss.sheriff.gateway.edge.error.ErrorResponseWriter;
import de.cuioss.sheriff.gateway.events.EventCategory;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayEventCounter;
import de.cuioss.sheriff.gateway.forward.TcpPeerGate;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The gate in front of a recon ledger filled to its bound with live sources, driven over a live Vert.x
 * server whose one handler stashes the source through {@link SourceKeys} and asks the gate: under
 * {@code block} and {@code delay} a new source the ledger cannot track is refused with the block-style
 * {@code 429} and the overflow is reported, while under {@code observe}, for the shared {@code unknown}
 * source, and for a ledger with room, nothing is refused. How the ledger decides that it has no room is
 * {@code SourceLedgerPressureTest}'s subject.
 */
@EnableTestLogger
@DisplayName("SourceGate — the refusal of a source a full ledger cannot track")
@Tag("isolated-fork")
class SourceGateFullLedgerTest {

    private static final String PASSED = "passed";
    /** Each filler source's weight: one below the default threshold, so it neither trips nor drains in the test. */
    private static final int FILLER_WEIGHT = EnumerationDetectionConfig.DEFAULT_THRESHOLD - 1;

    private Vertx vertx;
    private HttpServer server;
    private HttpClient client;
    private GatewayEventCounter eventCounter;
    private SimpleMeterRegistry meterRegistry;
    private final AtomicReference<SourceGate> gate = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        eventCounter = new GatewayEventCounter();
        meterRegistry = new SimpleMeterRegistry();
        SourceKeys sourceKeys = sourceKeys();
        Router router = Router.router(vertx);
        router.route().handler(ctx -> {
            sourceKeys.resolveAndStash(ctx);
            if (!gate.get().answersBlocked(ctx)) {
                ctx.response().setStatusCode(200).end(PASSED);
            }
        });
        server = Awaits.connect(vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                "the gate test server to start listening");
        client = vertx.createHttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        Awaits.teardown(client.close(), "the HTTP client to close");
        Awaits.teardown(server.close(), "the gate test server to close");
        Awaits.teardown(vertx.close(), "Vert.x to close");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = EnumerationDetectionConfig.Action.class, names = {"BLOCK", "DELAY"})
    @DisplayName("under block and delay a new source a full ledger cannot track is answered the block-style 429")
    void untrackableSourceIsRefused(EnumerationDetectionConfig.Action action) throws Exception {
        SourceGate sourceGate = gate(action);
        fill(sourceGate);

        Response response = send();

        EventCategory rateLimiting = EventCategory.RATE_LIMITING;
        String expectedBody = ErrorResponseWriter.problemBody(new GatewayJson(new ObjectMapper()),
                rateLimiting.problemType(), rateLimiting.title(), 429, Map.of());
        Counter blocked = meterRegistry.find(SheriffMetrics.ERRORS_TOTAL)
                .tags("route", SheriffMetrics.NO_ROUTE, "event", EventType.SOURCE_BLOCKED.name()).counter();
        Counter untracked = meterRegistry.find(SheriffMetrics.SOURCE_UNTRACKED_TOTAL).counter();
        assertAll("the refusal of the untrackable source",
                () -> assertTrue(sourceGate.ledger().state(loopbackKey()).isEmpty(),
                        "precondition: the requesting source is not tracked"),
                () -> assertEquals(429, response.status()),
                () -> assertEquals("1", response.headers().get(ErrorResponseWriter.RETRY_AFTER_HEADER),
                        "Retry-After is the sweep interval in whole seconds"),
                () -> assertEquals(expectedBody, response.body()),
                () -> assertEquals(1L, eventCounter.getCount(EventType.SOURCE_BLOCKED)),
                () -> assertNotNull(blocked, "sheriff_errors_total carries SOURCE_BLOCKED under the no-route label"),
                () -> assertNotNull(untracked, "the overflow is counted"),
                () -> assertEquals(1.0, untracked.count()));
        LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                ApiSheriffLogMessages.WARN.SOURCE_UNTRACKED.resolveIdentifierString());
    }

    @Test
    @DisplayName("under observe the same request against a full ledger passes")
    void observeRefusesNothing() throws Exception {
        fill(gate(EnumerationDetectionConfig.Action.OBSERVE));

        Response response = send();

        assertAll("observe never refuses",
                () -> assertEquals(200, response.status()),
                () -> assertEquals(PASSED, response.body()),
                () -> assertEquals(0L, eventCounter.getCount(EventType.SOURCE_BLOCKED)));
    }

    @Test
    @DisplayName("under block the unknown source against a full ledger is never refused")
    void unknownSourceIsNeverRefused() {
        SourceGate sourceGate = gate(EnumerationDetectionConfig.Action.BLOCK);
        fill(sourceGate);

        boolean answered = sourceGate.answersBlocked(stashedOnly(
                new SourceKey(SourceKey.UNKNOWN_SOURCE, SourceKey.UNDECLARED_HOST)));

        assertAll("the shared unknown source is exempt",
                () -> assertFalse(answered),
                () -> assertEquals(0L, eventCounter.getCount(EventType.SOURCE_BLOCKED)));
    }

    @Test
    @DisplayName("under block a new source passes while the ledger has room")
    void newSourceWithRoomPasses() throws Exception {
        SourceGate sourceGate = gate(EnumerationDetectionConfig.Action.BLOCK);
        sourceGate.ledger().admit(filler(0), FILLER_WEIGHT, EventType.NO_ROUTE_MATCHED);

        Response response = send();

        assertAll("room means no refusal",
                () -> assertEquals(200, response.status()),
                () -> assertEquals(0L, eventCounter.getCount(EventType.SOURCE_BLOCKED)));
    }

    /** Fills the gate's recon ledger to its bound with live sources other than the loopback client. */
    private static void fill(SourceGate sourceGate) {
        for (int index = 0; index < SourceLedger.MAX_TRACKED_SOURCES; index++) {
            sourceGate.ledger().admit(filler(index), FILLER_WEIGHT, EventType.NO_ROUTE_MATCHED);
        }
    }

    private static SourceKey filler(int index) {
        return new SourceKey("10." + (index >> 16) + "." + ((index >> 8) & 0xff) + "." + (index & 0xff),
                SourceKey.UNDECLARED_HOST);
    }

    private SourceGate gate(EnumerationDetectionConfig.Action action) {
        SourceGate built = new SourceGate(EnumerationDetectionConfig.builder().action(action).build(), eventCounter,
                new SheriffMetrics(meterRegistry), new GatewayJson(new ObjectMapper()), _ -> false, _ -> {
                });
        gate.set(built);
        return built;
    }

    /** The source-key stage of a gateway without trusted proxies: every request is keyed on its TCP peer. */
    private static SourceKeys sourceKeys() {
        ForwardedHeaderResolver forwardedResolver = new ForwardedHeaderResolver(
                ForwardedResolverConfig.builder().trustedProxies(Set.of())
                        .securityConfig(SecurityConfiguration.defaults()).build(),
                new SecurityEventCounter());
        return new SourceKeys(new SourceKeyResolver(List.of()), new TcpPeerGate(List.of()), forwardedResolver);
    }

    /**
     * A {@link RoutingContext} that answers only the stashed source; any other call — answering the
     * request included — fails the test.
     */
    private static RoutingContext stashedOnly(SourceKey key) {
        return (RoutingContext) Proxy.newProxyInstance(RoutingContext.class.getClassLoader(),
                new Class<?>[]{RoutingContext.class}, (_, method, args) -> {
                    if ("get".equals(method.getName()) && SourceKeys.SOURCE_KEY.equals(args[0])) {
                        return key;
                    }
                    throw new AssertionError("the gate must not call " + method.getName());
                });
    }

    /** The key a request from this test's client is tracked under: the loopback peer, host folded to {@code *}. */
    private static SourceKey loopbackKey() {
        return new SourceKey(SourceKeyResolver.canonicalSource(LoopbackHost.ADDRESS), SourceKey.UNDECLARED_HOST);
    }

    private Response send() throws Exception {
        RequestOptions options = new RequestOptions().setHost(LoopbackHost.ADDRESS)
                .setPort(server.actualPort()).setURI("/probe");
        CompletableFuture<Response> future = client.request(options)
                .compose(HttpClientRequest::send)
                .compose(response -> response.body().map(buffer -> new Response(response.statusCode(),
                        Map.of(ErrorResponseWriter.RETRY_AFTER_HEADER,
                                String.valueOf(response.getHeader(ErrorResponseWriter.RETRY_AFTER_HEADER))),
                        buffer == null ? "" : buffer.toString())))
                .toCompletionStage().toCompletableFuture();
        return Awaits.connect(future, "the gate's answer");
    }

    /** The terminal response, captured once its body has fully arrived. */
    private record Response(int status, Map<String, String> headers, String body) {
    }
}
