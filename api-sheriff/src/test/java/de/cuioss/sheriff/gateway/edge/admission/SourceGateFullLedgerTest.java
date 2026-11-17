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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
import de.cuioss.sheriff.gateway.detection.SourceState;
import de.cuioss.sheriff.gateway.edge.end.ResponseDelay;
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
 * <p>
 * Past the ledger's high-water mark a new IPv6 {@code /64} is tracked under its {@code /48} aggregate;
 * under {@code delay} the held-answer share of such a source is judged and counted under that tracked
 * {@code /48}, so its {@code /64}s share one share, while a {@code /64} tracked below the mark keeps its
 * own.
 */
@EnableTestLogger
@DisplayName("SourceGate — the refusal of a source a full ledger cannot track")
@Tag("isolated-fork")
class SourceGateFullLedgerTest {

    private static final String PASSED = "passed";
    /** Each filler source's weight: one below the default threshold, so it neither trips nor drains in the test. */
    private static final int FILLER_WEIGHT = EnumerationDetectionConfig.DEFAULT_THRESHOLD - 1;
    /** The fill from which the ledger tracks a new IPv6 {@code /64} under its {@code /48} aggregate. */
    private static final int HIGH_WATER_MARK = SourceLedger.MAX_TRACKED_SOURCES * SourceLedger.HIGH_WATER_PERCENT / 100;

    private Vertx vertx;
    private HttpServer server;
    private HttpClient client;
    private GatewayEventCounter eventCounter;
    private SimpleMeterRegistry meterRegistry;
    private final AtomicReference<SourceGate> gate = new AtomicReference<>();
    /** The sources whose held-answer share is used up: every key a held answer was counted under. */
    private final Set<SourceKey> usedUpShares = ConcurrentHashMap.newKeySet();
    /** The source the handler stashes in place of the loopback peer, when one is set. */
    private final AtomicReference<SourceKey> stashOverride = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        eventCounter = new GatewayEventCounter();
        meterRegistry = new SimpleMeterRegistry();
        SourceKeys sourceKeys = sourceKeys();
        Router router = Router.router(vertx);
        router.route().handler(ctx -> {
            sourceKeys.resolveAndStash(ctx);
            SourceKey override = stashOverride.get();
            if (override != null) {
                ctx.put(SourceKeys.SOURCE_KEY, override);
            }
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

    @Test
    @DisplayName("under delay two /64s of one tripped source tracked under its /48 share one held-answer share")
    void aggregatedSixtyFoursShareOneHeldShare() throws Exception {
        SourceGate sourceGate = gate(EnumerationDetectionConfig.Action.DELAY);
        fill(sourceGate, HIGH_WATER_MARK);
        SourceKey holder = ipv6Key("2001:db8:1:1::5");
        SourceKey rotated = ipv6Key("2001:db8:1:2::5");
        SourceKey aggregate = new SourceKey(SourceKeyResolver.aggregateSource(holder.source()),
                SourceKey.UNDECLARED_HOST);
        SourceState tripped = sourceGate.ledger().admit(holder, EnumerationDetectionConfig.DEFAULT_THRESHOLD,
                EventType.NO_ROUTE_MATCHED);
        Map<String, Object> holderData = heldRejection(sourceGate, holder);
        stashOverride.set(rotated);

        Response response = send();

        assertAll("one held-answer share for the whole /48",
                () -> assertEquals(aggregate, tripped.key(), "precondition: the /64 is tracked under its /48"),
                () -> assertTrue(tripped.hasTripped(), "precondition: the /48 tripped"),
                () -> assertEquals(aggregate, holderData.get(EdgeAdmission.HELD_SOURCE_KEY),
                        "the held answer counts against the tracked /48, not the requesting /64"),
                () -> assertEquals(429, response.status(), "another /64 of the /48 finds the share used up"),
                () -> assertEquals(1L, eventCounter.getCount(EventType.SOURCE_BLOCKED)));
    }

    @Test
    @DisplayName("under delay a /64 tracked below the high-water mark keeps its own held-answer share")
    void sixtyFourBelowTheMarkKeepsItsOwnShare() throws Exception {
        SourceGate sourceGate = gate(EnumerationDetectionConfig.Action.DELAY);
        SourceKey holder = ipv6Key("2001:db8:1:1::5");
        SourceKey sibling = ipv6Key("2001:db8:1:2::5");
        sourceGate.ledger().admit(holder, EnumerationDetectionConfig.DEFAULT_THRESHOLD, EventType.NO_ROUTE_MATCHED);
        SourceState siblingState = sourceGate.ledger().admit(sibling, EnumerationDetectionConfig.DEFAULT_THRESHOLD,
                EventType.NO_ROUTE_MATCHED);
        Map<String, Object> holderData = heldRejection(sourceGate, holder);

        stashOverride.set(sibling);
        Response siblingResponse = send();
        stashOverride.set(holder);
        Response holderResponse = send();

        assertAll("a /64 below the mark has a share of its own",
                () -> assertEquals(sibling, siblingState.key(), "precondition: the sibling is tracked under its /64"),
                () -> assertTrue(siblingState.hasTripped(), "precondition: the sibling tripped"),
                () -> assertEquals(holder, holderData.get(EdgeAdmission.HELD_SOURCE_KEY),
                        "the held answer counts against the /64 itself"),
                () -> assertEquals(200, siblingResponse.status(), "the sibling's own share is untouched"),
                () -> assertEquals(429, holderResponse.status(), "control: the holder's own share is used up"),
                () -> assertEquals(1L, eventCounter.getCount(EventType.SOURCE_BLOCKED)));
    }

    /**
     * Feeds one rejection of the tripped {@code source} to the gate and marks the share its held answer
     * was counted under as used up — the held-answer sub-budget of a one-permit share.
     */
    private Map<String, Object> heldRejection(SourceGate sourceGate, SourceKey source) {
        Map<String, Object> data = new HashMap<>(Map.of(SourceKeys.SOURCE_KEY, source,
                ResponseDelay.ADMITTED_AT_KEY, 0L));
        OptionalLong hold = sourceGate.admit(feedContext(data), null, EventType.NO_ROUTE_MATCHED);
        assertTrue(hold.isPresent(), "precondition: the tripped source's rejection is held");
        if (data.get(EdgeAdmission.HELD_SOURCE_KEY) instanceof SourceKey counted) {
            usedUpShares.add(counted);
        }
        return data;
    }

    /** Fills the gate's recon ledger to its bound with live sources other than the loopback client. */
    private static void fill(SourceGate sourceGate) {
        fill(sourceGate, SourceLedger.MAX_TRACKED_SOURCES);
    }

    /** Fills the gate's recon ledger with {@code count} live IPv4 sources other than the loopback client. */
    private static void fill(SourceGate sourceGate, int count) {
        for (int index = 0; index < count; index++) {
            sourceGate.ledger().admit(filler(index), FILLER_WEIGHT, EventType.NO_ROUTE_MATCHED);
        }
    }

    /** The key a request from {@code address} is tracked under below the mark: its {@code /64}, host folded. */
    private static SourceKey ipv6Key(String address) {
        return new SourceKey(SourceKeyResolver.canonicalSource(address), SourceKey.UNDECLARED_HOST);
    }

    private static SourceKey filler(int index) {
        return new SourceKey("10." + (index >> 16) + "." + ((index >> 8) & 0xff) + "." + (index & 0xff),
                SourceKey.UNDECLARED_HOST);
    }

    private SourceGate gate(EnumerationDetectionConfig.Action action) {
        SourceGate built = new SourceGate(EnumerationDetectionConfig.builder().action(action).build(), eventCounter,
                new SheriffMetrics(meterRegistry), new GatewayJson(new ObjectMapper()), usedUpShares::contains, _ -> {
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

    /**
     * A {@link RoutingContext} backed by a map for {@code get} and {@code put}, the only calls the
     * rejection feed makes on it; every other call fails the test.
     */
    private static RoutingContext feedContext(Map<String, Object> data) {
        return (RoutingContext) Proxy.newProxyInstance(RoutingContext.class.getClassLoader(),
                new Class<?>[]{RoutingContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> data.get(args[0]);
                    case "put" -> {
                        data.put((String) args[0], args[1]);
                        yield proxy;
                    }
                    default -> throw new AssertionError("the rejection feed must not call " + method.getName());
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
