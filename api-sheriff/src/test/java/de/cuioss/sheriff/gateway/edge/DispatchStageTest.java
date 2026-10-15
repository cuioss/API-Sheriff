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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.sheriff.gateway.asset.AssetSource;
import de.cuioss.sheriff.gateway.asset.DirectoryAssetSource;
import de.cuioss.sheriff.gateway.asset.PathConfinement;
import de.cuioss.sheriff.gateway.asset.UpstreamAssetSource;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedAsset;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayEventCounter;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.routing.ProtocolProcessorRegistry;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.sheriff.gateway.routing.RouteTable;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.sheriff.gateway.testsupport.UnreachablePort;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import io.smallrye.faulttolerance.api.CircuitBreakerState;
import io.smallrye.faulttolerance.api.Guard;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.streams.ReadStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@EnableGeneratorController
@DisplayName("DispatchStage — stage 6 streamed upstream dispatch")
@Tag("isolated-fork")
class DispatchStageTest {

    /** The {@code max_body_bytes} ceiling of the unit-level body streams, above every body they carry. */
    private static final long BODY_CAP = 1024L;

    /**
     * A proxy route together with the stage that serves it. The route's shared client and guard are
     * not route components: the stage resolves them from its {@link RouteAssembler.UpstreamClients}
     * lookup, which this fixture fills for the route exactly as the boot assembly would.
     */
    private record Routed(Route route, DispatchStage stage) {

        static Routed of(String id, ResolvedUpstream upstream, boolean retryEnabled, HttpClient client, Guard guard,
                long bodyIdleTimeoutMillis, UpstreamFailureMapper failureMapper) {
            Route route = Route.builder()
                    .id(id)
                    .match(MatchConfig.builder().pathPrefix("/" + id).build())
                    .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                    .upstream(upstream)
                    .retryEnabled(retryEnabled)
                    .build();
            RouteAssembler.UpstreamTarget target = RouteAssembler.UpstreamTarget.of(route);
            RouteAssembler.UpstreamClients clients = new RouteAssembler.UpstreamClients(Map.of(target, client),
                    Map.of(new RouteAssembler.ResilienceShape(target, retryEnabled), guard));
            return new Routed(route, new DispatchStage(clients, BODY_CAP, bodyIdleTimeoutMillis, failureMapper));
        }

        HttpClientResponse dispatch(HttpMethod method, String requestUri, Map<String, String> forwardHeaders,
                ReadStream<Buffer> requestBody, DispatchStage.BodyFraming framing) {
            return stage.dispatch(route, method, requestUri, forwardHeaders, requestBody, framing);
        }
    }

    @Nested
    @DisplayName("upstream request URI assembly")
    class UpstreamUri {

        private static final ResolvedUpstream UPSTREAM = new ResolvedUpstream("http", "orders-svc", 8080, "/base");

        @Test
        @DisplayName("appends path remainder and raw query to the upstream base path")
        void appendsRemainderAndQuery() {
            assertEquals("/base/orders?page=2",
                    DispatchStage.upstreamRequestUri(UPSTREAM, "/orders", "?page=2"));
        }

        @Test
        @DisplayName("omits the query when none is present")
        void omitsEmptyQuery() {
            assertEquals("/base/orders", DispatchStage.upstreamRequestUri(UPSTREAM, "/orders", ""));
        }

        @Test
        @DisplayName("strips a trailing slash on the upstream base path before appending")
        void stripsTrailingBasePathSlash() {
            ResolvedUpstream slashed = new ResolvedUpstream("http", "orders-svc", 8080, "/base/");
            assertEquals("/base/orders", DispatchStage.upstreamRequestUri(slashed, "/orders", ""));
        }

        @Test
        @DisplayName("strips EVERY trailing slash, so the dispatch and the Location rewrite agree")
        void stripsEveryTrailingBasePathSlash() {
            // One slash removed instead of all would forward /base//orders here, while
            // LocationRewriter — which strips the whole run before matching — would already have
            // mapped an upstream /base/orders onto the gateway. The client's follow-up would then be
            // dispatched to a different upstream path than the one the rewrite was derived from.
            // A base path reaches this method unnormalized whenever the alias URL carries the run,
            // since TopologyResolver takes URI.getPath() verbatim.
            ResolvedUpstream doubled = new ResolvedUpstream("http", "orders-svc", 8080, "/base//");
            ResolvedUpstream tripled = new ResolvedUpstream("http", "orders-svc", 8080, "/base///");

            assertAll("the dispatch normalizes a trailing run exactly as the rewriter does",
                    () -> assertEquals("/base/orders", DispatchStage.upstreamRequestUri(doubled, "/orders", "")),
                    () -> assertEquals("/base/orders", DispatchStage.upstreamRequestUri(tripled, "/orders", "")),
                    () -> assertEquals("/base/orders?page=2",
                            DispatchStage.upstreamRequestUri(doubled, "/orders", "?page=2")));
        }

        @Test
        @DisplayName("a base path that is only slashes collapses to the remainder")
        void allSlashBasePathCollapses() {
            // THE EDGE: stripping the whole run must not leave a stray separator behind either.
            ResolvedUpstream rootish = new ResolvedUpstream("http", "orders-svc", 8080, "//");
            assertEquals("/orders", DispatchStage.upstreamRequestUri(rootish, "/orders", ""));
        }
    }

    @Nested
    @DisplayName("streamed request body byte cap")
    class ByteCap {

        @Test
        @DisplayName("forwards each chunk immediately while the running count stays within the cap")
        void streamsChunksUnderCap() {
            // Arrange
            TestReadStream source = new TestReadStream();
            List<Buffer> forwarded = new ArrayList<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean aborted = new AtomicBoolean();
            DispatchStage.ByteCappedBodyStream capped =
                    new DispatchStage.ByteCappedBodyStream(source, 10L, () -> aborted.set(true));
            capped.handler(forwarded::add);
            capped.exceptionHandler(failure::set);

            // Act — 5 + 4 = 9 bytes, both under the 10-byte cap
            source.emit(Buffer.buffer("12345"));
            assertEquals(1, forwarded.size(), "first chunk must be forwarded immediately, not buffered");
            source.emit(Buffer.buffer("6789"));

            // Assert
            assertEquals(2, forwarded.size(), "each in-cap chunk streams through as it arrives");
            assertNull(failure.get(), "no failure while under the cap");
            assertFalse(aborted.get(), "the upstream call is not aborted while under the cap");
        }

        @Test
        @DisplayName("aborts the in-flight upstream call and fails the stream when the cap is breached")
        void abortsOnBreach() {
            // Arrange
            TestReadStream source = new TestReadStream();
            List<Buffer> forwarded = new ArrayList<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean aborted = new AtomicBoolean();
            DispatchStage.ByteCappedBodyStream capped =
                    new DispatchStage.ByteCappedBodyStream(source, 10L, () -> aborted.set(true));
            capped.handler(forwarded::add);
            capped.exceptionHandler(failure::set);

            // Act — 6 bytes (ok), then 6 more crossing the 10-byte cap
            source.emit(Buffer.buffer("123456"));
            source.emit(Buffer.buffer("ABCDEF"));

            // Assert — the breaching chunk is not forwarded, the call is aborted, and a 413 is raised
            assertEquals(1, forwarded.size(), "the breaching chunk must never cross to the upstream");
            assertTrue(aborted.get(), "a mid-stream breach must abort the in-flight upstream call");
            Throwable raised = failure.get();
            GatewayException gatewayException = assertInstanceOf(GatewayException.class, raised);
            assertEquals(EventType.CONTENT_TOO_LARGE, gatewayException.getEventType());
        }

        @Test
        @DisplayName("ignores further chunks once the stream has aborted")
        void ignoresChunksAfterAbort() {
            // Arrange
            TestReadStream source = new TestReadStream();
            List<Buffer> forwarded = new ArrayList<>();
            DispatchStage.ByteCappedBodyStream capped =
                    new DispatchStage.ByteCappedBodyStream(source, 4L, () -> {
                    });
            capped.handler(forwarded::add);
            capped.exceptionHandler(t -> {
            });

            // Act
            source.emit(Buffer.buffer("12345"));  // 5 bytes → immediate breach
            source.emit(Buffer.buffer("late"));    // must be ignored

            // Assert
            assertTrue(forwarded.isEmpty(), "no chunk crosses once the very first breaches the cap");
        }

        @Test
        @DisplayName("forwards a body of exactly its declared length whole and passes its end on")
        void forwardsABodyOfExactlyItsDeclaredLength() {
            String first = Generators.letterStrings(2, 6).next();
            String second = Generators.letterStrings(2, 6).next();
            BodyProbe probe = new BodyProbe(first.length() + second.length());

            probe.source.emit(Buffer.buffer(first));
            probe.source.emit(Buffer.buffer(second));
            probe.source.end();

            assertAll("a body of exactly its declared length",
                    () -> assertEquals(first + second, probe.forwardedText(), "the whole body crosses to the upstream"),
                    () -> assertTrue(probe.ended.get(), "the end of a whole body is passed on"),
                    () -> assertNull(probe.failure.get(), "a whole body raises no failure"),
                    () -> assertEquals(0, probe.aborts.get(), "a whole body never aborts the upstream request"));
        }

        @Test
        @DisplayName("never forwards a chunk crossing the declared length: refuses it and aborts once")
        void refusesAChunkCrossingTheDeclaredLength() {
            String within = Generators.letterStrings(2, 6).next();
            BodyProbe probe = new BodyProbe(within.length() + 1L);

            probe.source.emit(Buffer.buffer(within));
            probe.source.emit(Buffer.buffer(Generators.letterStrings(2, 6).next()));
            probe.source.end();

            assertAll("a chunk crossing the declared length",
                    () -> assertEquals(within, probe.forwardedText(), "no byte beyond the declared length crosses"),
                    () -> assertEquals(EventType.SECURITY_FILTER_VIOLATION,
                            assertInstanceOf(GatewayException.class, probe.failure.get()).getEventType()),
                    () -> assertEquals(1, probe.aborts.get(), "the upstream request is aborted exactly once"),
                    () -> assertFalse(probe.ended.get(), "the end after a refusal is not passed on"));
        }

        @Test
        @DisplayName("an end short of the declared length aborts once as INBOUND_BODY_ABORTED instead of ending")
        void abortsAnEndShortOfTheDeclaredLength() {
            String partial = Generators.letterStrings(2, 6).next();
            BodyProbe probe = new BodyProbe(partial.length() + 1L);

            probe.source.emit(Buffer.buffer(partial));
            probe.source.end();
            probe.source.end();

            assertAll("an end short of the declared length",
                    () -> assertEquals(partial, probe.forwardedText()),
                    () -> assertEquals(EventType.INBOUND_BODY_ABORTED,
                            assertInstanceOf(GatewayException.class, probe.failure.get()).getEventType(),
                            "a body that did not arrive whole is client-caused, not a security filter violation"),
                    () -> assertEquals(1, probe.aborts.get(), "the upstream request is aborted exactly once"),
                    () -> assertFalse(probe.ended.get(), "a short body is never ended towards the upstream"));
        }

        @Test
        @DisplayName("with no declared length only the cap applies: the body streams through and ends")
        void appliesNoLengthBoundWithoutADeclaredLength() {
            String first = Generators.letterStrings(2, 6).next();
            String second = Generators.letterStrings(2, 6).next();
            BodyProbe probe = new BodyProbe(DispatchStage.NO_DECLARED_LENGTH);

            probe.source.emit(Buffer.buffer(first));
            probe.source.emit(Buffer.buffer(second));
            probe.source.end();

            assertAll("a body declaring no length",
                    () -> assertEquals(first + second, probe.forwardedText()),
                    () -> assertTrue(probe.ended.get()),
                    () -> assertNull(probe.failure.get()),
                    () -> assertEquals(0, probe.aborts.get()));
        }

        @Test
        @DisplayName("a cleared end handler is no longer called")
        void clearedEndHandlerIsNotCalled() {
            BodyProbe probe = new BodyProbe(DispatchStage.NO_DECLARED_LENGTH);

            probe.capped.endHandler(null);
            probe.source.end();

            assertFalse(probe.ended.get(), "the stream must stop passing its end on once the handler is cleared");
        }
    }

    /**
     * A {@link DispatchStage.ByteCappedBodyStream} over a {@link TestReadStream}, held to one declared
     * length under {@link #BODY_CAP}, recording what it forwards, its failure, its end and every abort.
     */
    private static final class BodyProbe {

        private final TestReadStream source = new TestReadStream();
        private final List<Buffer> forwarded = new ArrayList<>();
        private final AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        private final AtomicInteger aborts = new AtomicInteger();
        private final AtomicBoolean ended = new AtomicBoolean();
        private final DispatchStage.ByteCappedBodyStream capped;

        BodyProbe(long declaredLength) {
            capped = new DispatchStage.ByteCappedBodyStream(source, BODY_CAP, declaredLength, aborts::incrementAndGet,
                    _ -> {
                        // The forwarded count feeds the retry gate, which these cases do not exercise.
                    });
            capped.handler(forwarded::add);
            capped.exceptionHandler(failure::set);
            capped.endHandler(_ -> ended.set(true));
        }

        String forwardedText() {
            Buffer joined = Buffer.buffer();
            forwarded.forEach(joined::appendBuffer);
            return joined.toString();
        }
    }

    /**
     * The request-body idle clock of {@link DispatchStage.ByteCappedBodyStream}, driven by hand: time
     * moves only when a case advances the {@link ManualTime}, so every deadline is exact and no case
     * waits on real time.
     */
    @Nested
    @DisplayName("request-body idle clock of the byte-capped body")
    class BodyIdleClock {

        private static final long TIMEOUT_MILLIS = 1_000L;

        @Test
        @DisplayName("no chunk before the deadline: expires as REQUEST_BODY_TIMEOUT, aborts once and propagates the failure")
        void noChunkBeforeTheDeadlineExpires() {
            IdleProbe probe = new IdleProbe(TIMEOUT_MILLIS, DispatchStage.NO_DECLARED_LENGTH);
            probe.capped.startIdleClock();

            probe.time.advanceMillis(TIMEOUT_MILLIS - 1);
            boolean quietBeforeTheDeadline = probe.failure.get() == null && probe.aborts.get() == 0;
            probe.time.advanceMillis(1);
            GatewayException expiry = probe.capped.clientAbort();
            probe.time.advanceMillis(5 * TIMEOUT_MILLIS);

            assertAll("a body with no chunk before the deadline",
                    () -> assertTrue(quietBeforeTheDeadline, "nothing may expire before the whole timeout passed"),
                    () -> assertEquals(EventType.REQUEST_BODY_TIMEOUT,
                            assertInstanceOf(GatewayException.class, expiry).getEventType(),
                            "the expiry is recorded as the stream's client abort"),
                    () -> assertEquals(408, EventType.REQUEST_BODY_TIMEOUT.httpStatus()),
                    () -> assertSame(expiry, probe.failure.get(), "the expiry is propagated to the consumer"),
                    () -> assertEquals(1, probe.aborts.get(), "the abort action runs exactly once"),
                    () -> assertTrue(probe.source.paused(), "the inbound stream is paused by the expiry"),
                    () -> assertEquals(0, probe.time.pendingCount(), "nothing is pending after the expiry"));
        }

        @Test
        @DisplayName("a chunk before the deadline: the old deadline re-arms, and expiry comes one full timeout after the chunk")
        void chunkBeforeTheDeadlineRestartsTheGap() {
            IdleProbe probe = new IdleProbe(TIMEOUT_MILLIS, DispatchStage.NO_DECLARED_LENGTH);
            probe.capped.startIdleClock();
            probe.time.advanceMillis(600);
            probe.source.emit(Buffer.buffer(Generators.letterStrings(2, 6).next()));

            probe.time.advanceMillis(400);
            boolean quietAtTheOldDeadline = probe.failure.get() == null;
            int scheduledAtTheOldDeadline = probe.time.scheduledCount();
            probe.time.advanceMillis(TIMEOUT_MILLIS - 400 - 1);
            boolean quietJustBeforeTheNewDeadline = probe.failure.get() == null;
            probe.time.advanceMillis(1);

            assertAll("a chunk before the deadline",
                    () -> assertEquals(1, probe.forwarded.size(), "the chunk is forwarded"),
                    () -> assertTrue(quietAtTheOldDeadline, "the timer firing at the old deadline does not expire"),
                    () -> assertEquals(2, scheduledAtTheOldDeadline,
                            "the timer firing at the old deadline re-arms for the remainder"),
                    () -> assertTrue(quietJustBeforeTheNewDeadline,
                            "nothing expires before one full timeout after the last chunk"),
                    () -> assertEquals(EventType.REQUEST_BODY_TIMEOUT,
                            assertInstanceOf(GatewayException.class, probe.capped.clientAbort()).getEventType(),
                            "one full timeout after the last chunk the body expires"),
                    () -> assertEquals(1, probe.aborts.get()));
        }

        @ParameterizedTest(name = "restarted by {0}")
        @ValueSource(strings = {"resume", "fetch"})
        @DisplayName("paused by the consumer: no expiry while paused, and the gap restarts when the flow is restarted")
        void pauseIsNotCounted(String restart) {
            IdleProbe probe = new IdleProbe(TIMEOUT_MILLIS, DispatchStage.NO_DECLARED_LENGTH);
            probe.capped.startIdleClock();
            probe.capped.pause();

            probe.time.advanceMillis(5 * TIMEOUT_MILLIS);
            boolean quietWhilePaused = probe.failure.get() == null;
            if ("resume".equals(restart)) {
                probe.capped.resume();
            } else {
                probe.capped.fetch(1);
            }
            probe.time.advanceMillis(TIMEOUT_MILLIS - 1);
            boolean quietJustBeforeTheDeadline = probe.failure.get() == null;
            probe.time.advanceMillis(1);

            assertAll("a body paused by its consumer",
                    () -> assertTrue(quietWhilePaused, "the upstream's backpressure is never charged to the request"),
                    () -> assertTrue(quietJustBeforeTheDeadline, "the gap restarts from the moment the flow restarts"),
                    () -> assertEquals(EventType.REQUEST_BODY_TIMEOUT,
                            assertInstanceOf(GatewayException.class, probe.capped.clientAbort()).getEventType()),
                    () -> assertEquals(1, probe.aborts.get()));
        }

        @Test
        @DisplayName("the end of the inbound body cancels the clock")
        void inboundEndCancelsTheClock() {
            IdleProbe probe = new IdleProbe(TIMEOUT_MILLIS, DispatchStage.NO_DECLARED_LENGTH);
            probe.capped.startIdleClock();

            probe.source.end();
            int pendingAfterTheEnd = probe.time.pendingCount();
            probe.time.advanceMillis(5 * TIMEOUT_MILLIS);

            assertAll("a body that ended",
                    () -> assertEquals(0, pendingAfterTheEnd, "the end cancels the pending timer"),
                    () -> assertTrue(probe.ended.get(), "the end is passed on"),
                    () -> assertNull(probe.failure.get(), "an ended body never expires"),
                    () -> assertNull(probe.capped.clientAbort()),
                    () -> assertEquals(0, probe.aborts.get()));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(EarlierAbort.class)
        @DisplayName("an abort claimed first stops the clock: a later expiry changes nothing")
        void earlierAbortKeepsItsClaim(EarlierAbort earlier) {
            IdleProbe probe = new IdleProbe(TIMEOUT_MILLIS, earlier.declaredLength);
            probe.capped.startIdleClock();

            earlier.trigger(probe);
            GatewayException claimed = probe.capped.clientAbort();
            probe.time.advanceMillis(5 * TIMEOUT_MILLIS);

            assertAll(earlier.name(),
                    () -> assertEquals(earlier.expected, claimed == null ? null : claimed.getEventType(),
                            "the earlier trigger owns the abort claim"),
                    () -> assertSame(claimed, probe.capped.clientAbort(), "the expiry does not replace the claim"),
                    () -> assertEquals(1, probe.aborts.get(), "the abort action runs once, for the earlier trigger"),
                    () -> assertEquals(0, probe.time.pendingCount(), "the earlier abort cancels the pending timer"),
                    () -> assertFalse(probe.failure.get() instanceof GatewayException timeout
                            && timeout.getEventType() == EventType.REQUEST_BODY_TIMEOUT,
                            "no expiry is propagated after the earlier abort"));
        }

        @Test
        @DisplayName("NO_BODY_IDLE_TIMEOUT: the clock is never armed and nothing is ever scheduled")
        void unarmedClockSchedulesNothing() {
            IdleProbe probe = new IdleProbe(DispatchStage.NO_BODY_IDLE_TIMEOUT, DispatchStage.NO_DECLARED_LENGTH);

            probe.capped.startIdleClock();
            probe.source.emit(Buffer.buffer(Generators.letterStrings(2, 6).next()));
            probe.capped.pause();
            probe.capped.resume();
            probe.capped.fetch(1);
            probe.time.advanceMillis(60 * TIMEOUT_MILLIS);
            probe.capped.stopIdleClock();

            assertAll("an unarmed idle clock",
                    () -> assertEquals(0, probe.time.scheduledCount(), "nothing is ever scheduled"),
                    () -> assertNull(probe.failure.get()),
                    () -> assertNull(probe.capped.clientAbort()),
                    () -> assertEquals(0, probe.aborts.get()));
        }
    }

    /** An abort that claims the stream before its idle clock would expire, with the event it records. */
    enum EarlierAbort {
        CAP_BREACH(DispatchStage.NO_DECLARED_LENGTH, EventType.CONTENT_TOO_LARGE),
        FRAMING_REFUSAL(4L, EventType.SECURITY_FILTER_VIOLATION),
        INBOUND_FAILURE(DispatchStage.NO_DECLARED_LENGTH, EventType.INBOUND_BODY_ABORTED),
        /** The gateway's own reset: it aborts the upstream request, but records no client abort. */
        GATEWAY_RESET(DispatchStage.NO_DECLARED_LENGTH, null);

        private final long declaredLength;
        private final @Nullable EventType expected;

        EarlierAbort(long declaredLength, @Nullable EventType expected) {
            this.declaredLength = declaredLength;
            this.expected = expected;
        }

        void trigger(IdleProbe probe) {
            switch (this) {
                case CAP_BREACH -> probe.source.emit(Buffer.buffer(new byte[(int) BODY_CAP + 1]));
                case FRAMING_REFUSAL -> probe.source.emit(Buffer.buffer(new byte[(int) declaredLength + 1]));
                case INBOUND_FAILURE -> probe.source.fail(new IllegalStateException(Generators.letterStrings(4, 12).next()));
                case GATEWAY_RESET -> probe.inbound.resetByGateway();
            }
        }
    }

    /**
     * A {@link DispatchStage.ByteCappedBodyStream} over an armed {@link DispatchStage.InboundBody}, as the
     * dispatch builds one, whose idle clock runs on a {@link ManualTime}; it records what it forwards, its
     * failure, its end and every abort.
     */
    private static final class IdleProbe {

        private final TestReadStream source = new TestReadStream();
        private final DispatchStage.InboundBody inbound = DispatchStage.InboundBody.arm(source);
        private final ManualTime time = new ManualTime();
        private final List<Buffer> forwarded = new ArrayList<>();
        private final AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        private final AtomicInteger aborts = new AtomicInteger();
        private final AtomicBoolean ended = new AtomicBoolean();
        private final DispatchStage.ByteCappedBodyStream capped;

        IdleProbe(long timeoutMillis, long declaredLength) {
            capped = new DispatchStage.ByteCappedBodyStream(inbound, BODY_CAP, declaredLength, aborts::incrementAndGet,
                    _ -> {
                        // The forwarded count feeds the retry gate, which these cases do not exercise.
                    }, new DispatchStage.IdleClock(timeoutMillis, time, time::nanoTime));
            capped.handler(forwarded::add);
            capped.exceptionHandler(failure::set);
            capped.endHandler(_ -> ended.set(true));
        }
    }

    /**
     * A hand-driven clock and scheduler: time stands still until {@link #advanceMillis(long)} moves it,
     * and every task falling due on the way runs then, in due order, on the calling thread.
     */
    private static final class ManualTime implements DispatchStage.IdleScheduler {

        /** An arbitrary non-zero origin, so no case relies on the clock starting at zero. */
        private long nowNanos = TimeUnit.SECONDS.toNanos(42);
        private long lastId;
        private int scheduled;
        private final Map<Long, ScheduledTask> pending = new LinkedHashMap<>();

        @Override
        public long schedule(long delayMillis, Runnable task) {
            if (delayMillis <= 0) {
                throw new IllegalArgumentException("the idle clock schedules a positive delay, got " + delayMillis);
            }
            long id = ++lastId;
            pending.put(id, new ScheduledTask(nowNanos + TimeUnit.MILLISECONDS.toNanos(delayMillis), task));
            scheduled++;
            return id;
        }

        @Override
        public void cancel(long timerId) {
            pending.remove(timerId);
        }

        long nanoTime() {
            return nowNanos;
        }

        /** Moves time forward by {@code millis}, running every task that falls due on the way. */
        void advanceMillis(long millis) {
            long target = nowNanos + TimeUnit.MILLISECONDS.toNanos(millis);
            while (true) {
                Map.Entry<Long, ScheduledTask> due = pending.entrySet().stream()
                        .filter(entry -> entry.getValue().dueNanos() <= target)
                        .min(Comparator.comparingLong((Map.Entry<Long, ScheduledTask> entry) -> entry.getValue().dueNanos())
                                .thenComparingLong(Map.Entry::getKey))
                        .orElse(null);
                if (due == null) {
                    break;
                }
                pending.remove(due.getKey());
                nowNanos = Math.max(nowNanos, due.getValue().dueNanos());
                due.getValue().task().run();
            }
            nowNanos = target;
        }

        /** @return the tasks scheduled and neither run nor cancelled yet */
        int pendingCount() {
            return pending.size();
        }

        /** @return every task ever scheduled */
        int scheduledCount() {
            return scheduled;
        }

        private record ScheduledTask(long dueNanos, Runnable task) {
        }
    }

    /**
     * The decorator pair the edge's post-response reset goes through: {@link DispatchStage.InboundBody}
     * records that the gateway reset the stream and delivers the fixed gateway-reset cause, and the
     * {@link DispatchStage.ByteCappedBodyStream} over it stops forwarding on that cause without
     * recording a client abort.
     */
    @Nested
    @DisplayName("gateway-made stream reset through the inbound-body decorator pair")
    class GatewayReset {

        private final TestReadStream source = new TestReadStream();
        private final DispatchStage.InboundBody inbound = DispatchStage.InboundBody.arm(source);
        private final List<Buffer> forwarded = new ArrayList<>();
        private final AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        private final AtomicInteger aborts = new AtomicInteger();

        /** Subscribes a byte-capped body to the armed decorator, as the dispatch does. */
        private DispatchStage.ByteCappedBodyStream subscribe() {
            DispatchStage.ByteCappedBodyStream capped = new DispatchStage.ByteCappedBodyStream(inbound, BODY_CAP,
                    DispatchStage.NO_DECLARED_LENGTH, aborts::incrementAndGet, _ -> {
                        // The forwarded count feeds the retry gate, which these cases do not exercise.
                    });
            capped.handler(forwarded::add);
            capped.exceptionHandler(failure::set);
            return capped;
        }

        @Test
        @DisplayName("a reset after the body is subscribed aborts the upstream request once and records no client abort")
        void resetAfterSubscriptionAbortsWithoutAClientAbort() {
            DispatchStage.ByteCappedBodyStream capped = subscribe();
            source.emit(Buffer.buffer(Generators.letterStrings(2, 6).next()));

            inbound.resetByGateway();
            source.emit(Buffer.buffer(Generators.letterStrings(2, 6).next()));

            assertAll("a gateway reset of a subscribed body",
                    () -> assertEquals(1, aborts.get(), "the upstream request is reset exactly once"),
                    () -> assertNull(capped.clientAbort(), "a gateway-made reset is never a client abort"),
                    () -> assertNull(failure.get(), "the gateway reset is not reported to the consumer as a failure"),
                    () -> assertEquals(1, forwarded.size(), "nothing is forwarded after the reset"));
        }

        @Test
        @DisplayName("a reset before the body is subscribed reaches the later subscriber the same way")
        void resetBeforeSubscriptionIsReplayedToTheLaterSubscriber() {
            inbound.resetByGateway();

            DispatchStage.ByteCappedBodyStream capped = subscribe();

            assertAll("a gateway reset before subscription",
                    () -> assertEquals(1, aborts.get(), "the later subscriber still resets the upstream request once"),
                    () -> assertNull(capped.clientAbort(), "a gateway-made reset is never a client abort"),
                    () -> assertNull(failure.get(), "the gateway reset is not reported to the consumer as a failure"));
        }

        @Test
        @DisplayName("a transport failure after the reset is swallowed, never replayed as a client failure")
        void transportFailureAfterTheResetIsSwallowed() {
            DispatchStage.ByteCappedBodyStream capped = subscribe();
            inbound.resetByGateway();

            source.fail(new IllegalStateException(Generators.letterStrings(4, 12).next()));
            inbound.resetByGateway();

            assertAll("a transport failure surfacing after the gateway reset",
                    () -> assertEquals(1, aborts.get(), "the upstream request is reset once, not again"),
                    () -> assertNull(capped.clientAbort(), "the echo of the gateway's own reset is no client abort"),
                    () -> assertNull(failure.get(), "the echo of the gateway's own reset is never propagated"));
        }

        @Test
        @DisplayName("a genuine inbound failure without a gateway reset still records INBOUND_BODY_ABORTED")
        void genuineFailureStillRecordsTheClientAbort() {
            DispatchStage.ByteCappedBodyStream capped = subscribe();
            IllegalStateException clientDrop = new IllegalStateException(Generators.letterStrings(4, 12).next());

            source.fail(clientDrop);
            inbound.resetByGateway();

            GatewayException clientAbort = capped.clientAbort();
            assertAll("a genuine inbound failure",
                    () -> assertEquals(EventType.INBOUND_BODY_ABORTED,
                            assertInstanceOf(GatewayException.class, clientAbort).getEventType()),
                    () -> assertSame(clientDrop, failure.get(), "the genuine failure reaches the consumer"),
                    () -> assertEquals(1, aborts.get(),
                            "a gateway reset after the client abort does not reset the upstream request again"));
        }

        @Test
        @DisplayName("a reset pauses the body for good: a later resume or fetch — the pipe's — does not read it again")
        void resetPausesTheBodyForGood() {
            subscribe();
            inbound.resume();

            inbound.resetByGateway();
            boolean pausedByTheReset = source.paused();
            inbound.resume();
            inbound.fetch(1);

            assertAll("a body the gateway reset",
                    () -> assertTrue(pausedByTheReset, "the reset pauses the inbound stream"),
                    () -> assertTrue(source.paused(), "a resume or fetch after the reset leaves it paused"));
        }

        @Test
        @DisplayName("without a reset, resume and fetch reach the inbound stream as before")
        void withoutAResetResumeAndFetchReachTheStream() {
            subscribe();
            inbound.pause();

            inbound.resume();
            boolean pausedAfterResume = source.paused();
            inbound.pause();
            inbound.fetch(1);

            assertAll("a body the gateway did not reset",
                    () -> assertFalse(pausedAfterResume, "a resume reaches the inbound stream"),
                    () -> assertFalse(source.paused(), "a fetch reaches the inbound stream"));
        }

        @Test
        @DisplayName("the gateway-reset cause carries no stack trace and is one shared instance")
        void gatewayResetCauseIsStacklessAndShared() {
            AtomicReference<@Nullable Throwable> delivered = new AtomicReference<>();
            inbound.exceptionHandler(delivered::set);

            inbound.resetByGateway();

            assertAll("the gateway-reset cause",
                    () -> assertSame(DispatchStage.GatewayStreamReset.INSTANCE, delivered.get()),
                    () -> assertEquals(0, DispatchStage.GatewayStreamReset.INSTANCE.getStackTrace().length));
        }
    }

    @Nested
    @DisplayName("bodyless-method watch over the inbound stream")
    class BodylessWatch {

        private static final String ABORT = "abort";
        private static final String LATE = "late";

        private final TestReadStream source = new TestReadStream();
        /** The abort action and the late-violation callback, in the order they ran. */
        private final List<String> sequence = new CopyOnWriteArrayList<>();
        private final List<GatewayException> lateViolations = new CopyOnWriteArrayList<>();
        private DispatchStage.BodylessMethodWatch watch;

        @BeforeEach
        void arm() {
            watch = DispatchStage.BodylessMethodWatch.arm(source, () -> sequence.add(ABORT), violation -> {
                sequence.add(LATE);
                lateViolations.add(violation);
            });
        }

        @Test
        @DisplayName("consumes the inbound stream in place of the upstream request")
        void consumesTheInboundStream() {
            assertTrue(source.subscribed(), "the watch must read the stream the upstream request never receives");
        }

        @Test
        @DisplayName("the first body byte refuses the request as SECURITY_FILTER_VIOLATION and aborts once")
        void firstBodyByteRefusesTheRequest() {
            // The marker keeps a short generated chunk from occurring in the fixed disposition by chance.
            String chunk = "client-chosen-body-" + Generators.letterStrings(1, 8).next();

            source.emit(Buffer.buffer(chunk));

            GatewayException refusal = watch.clientAbort();
            assertAll("the first body byte on a bodyless method",
                    () -> assertEquals(EventType.SECURITY_FILTER_VIOLATION,
                            assertInstanceOf(GatewayException.class, refusal).getEventType()),
                    () -> assertFalse(assertInstanceOf(GatewayException.class, refusal).getMessage().contains(chunk),
                            "the disposition is fixed and carries nothing the client sent"),
                    () -> assertEquals(List.of(ABORT), sequence, "the upstream request is aborted exactly once"));
        }

        @Test
        @DisplayName("an empty chunk and the end of the stream are not a body")
        void emptyChunkAndEndAreNotABody() {
            source.emit(Buffer.buffer());
            source.end();

            assertAll("an empty chunk and the end of the stream",
                    () -> assertNull(watch.clientAbort(), "nothing may be refused"),
                    () -> assertEquals(List.of(), sequence, "nothing may be aborted"),
                    () -> assertNull(watch.handOver(), "the response is handed over with no refusal"));
        }

        @Test
        @DisplayName("a further body byte after the refusal is ignored")
        void furtherBodyByteIsIgnored() {
            source.emit(Buffer.buffer(Generators.letterStrings(1, 8).next()));
            GatewayException first = watch.clientAbort();

            source.emit(Buffer.buffer(Generators.letterStrings(1, 8).next()));

            assertAll("a further body byte",
                    () -> assertSame(first, watch.clientAbort(), "the refusal is recorded once"),
                    () -> assertEquals(List.of(ABORT), sequence, "the upstream request is aborted once"));
        }

        @Test
        @DisplayName("a refusal before the hand-over is the dispatch's own failure, never a late one")
        void refusalBeforeTheHandOverIsReturnedByIt() {
            source.emit(Buffer.buffer(Generators.letterStrings(1, 8).next()));

            GatewayException returned = watch.handOver();

            assertAll("a refusal before the hand-over",
                    () -> assertSame(watch.clientAbort(), returned, "the hand-over reports the refusal"),
                    () -> assertEquals(List.of(), lateViolations, "the late-violation callback is not run"));
        }

        @Test
        @DisplayName("a refusal after the hand-over reaches the late-violation callback before the abort")
        void refusalAfterTheHandOverGoesToTheLateCallback() {
            GatewayException beforeAnyByte = watch.handOver();

            source.emit(Buffer.buffer(Generators.letterStrings(1, 8).next()));

            assertAll("a refusal after the hand-over",
                    () -> assertNull(beforeAnyByte, "nothing was refused when the response was handed over"),
                    () -> assertEquals(List.of(watch.clientAbort()), lateViolations,
                            "the refusal is delivered to the late-violation callback once"),
                    () -> assertEquals(List.of(LATE, ABORT), sequence,
                            "the edge must learn of the refusal before the upstream request is reset"));
        }
    }

    @Nested
    @DisplayName("body framing of a dispatched request")
    class Framing {

        @ParameterizedTest(name = "{0} declaring {1} is bodyless: {2}")
        @CsvSource({"GET, -1, true", "GET, 0, true", "GET, 7, false", "HEAD, -1, true", "HEAD, 0, true",
                "HEAD, 7, false", "POST, -1, false", "PUT, 0, false"})
        @DisplayName("a request is bodyless exactly when it is a GET or HEAD without a positive declared length")
        void bodylessExactlyForGetAndHeadWithoutAPositiveLength(HttpMethod method, long declaredLength,
                boolean bodyless) {
            DispatchStage.BodyFraming framing = DispatchStage.BodyFraming.of(method, declaredLength, _ -> {
                // No late refusal is delivered in this case.
            });

            assertAll("the framing of " + method,
                    () -> assertEquals(bodyless, framing.bodyless()),
                    () -> assertEquals(declaredLength, framing.declaredLength()));
        }

        @Test
        @DisplayName("a streamed framing is never bodyless and receives no late refusal")
        void streamedFramingIsNeverBodyless() {
            DispatchStage.BodyFraming framing = DispatchStage.BodyFraming.streamed(DispatchStage.NO_DECLARED_LENGTH);
            GatewayException violation = new GatewayException(EventType.SECURITY_FILTER_VIOLATION,
                    Generators.letterStrings(4, 12).next());

            assertAll("a streamed framing",
                    () -> assertFalse(framing.bodyless()),
                    () -> assertEquals(DispatchStage.NO_DECLARED_LENGTH, framing.declaredLength()),
                    () -> assertDoesNotThrow(() -> framing.lateViolation().accept(violation),
                            "its late-violation callback accepts and ignores a refusal"));
        }
    }

    /**
     * The bodyless dispatch path against a live upstream: the inbound stream is never handed to an
     * upstream request, so a bodyless request stays retryable while no body byte has arrived, and a
     * body byte refuses it wherever in the exchange it lands before the response is handed over.
     */
    @Nested
    @DisplayName("bodyless dispatch over a live upstream")
    class BodylessDispatch {

        private static final String FAILING_PATH = "/failing";
        private static final String HELD_PATH = "/held";
        private static final String ANSWERING_PATH = "/answering";

        private Vertx vertx;
        private ExecutorService virtualThreadExecutor;
        private HttpServer upstream;
        private HttpClient upstreamClient;
        /** Every request the stub upstream received a head for. */
        private final AtomicInteger upstreamStarted = new AtomicInteger();
        /** Every body byte that reached the stub upstream. */
        private final AtomicLong upstreamBodyBytes = new AtomicLong();
        private final List<GatewayException> lateViolations = new CopyOnWriteArrayList<>();

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
            // The stub upstream drops the connection of a request to the failing path as soon as its
            // head arrives, never answers one to the held path, and answers every other once it ended.
            upstream = Awaits.connect(vertx.createHttpServer().requestHandler(request -> {
                upstreamStarted.incrementAndGet();
                request.handler(chunk -> upstreamBodyBytes.addAndGet(chunk.length()));
                switch (request.path()) {
                    case FAILING_PATH -> request.connection().close();
                    case HELD_PATH -> {
                        // Received, never answered.
                    }
                    default -> request.endHandler(_ -> request.response().end("ok"));
                }
            }).listen(0, LoopbackHost.ADDRESS), "the stub upstream to start listening");
            upstreamClient = vertx.createHttpClient();
        }

        @AfterEach
        void tearDown() throws Exception {
            Awaits.teardown(upstreamClient.close(), "the upstream client to close");
            Awaits.teardown(upstream.close(), "the stub upstream to close");
            virtualThreadExecutor.close();
            Awaits.teardown(vertx.close(), "Vert.x to close");
        }

        @Test
        @DisplayName("a bodyless GET is retried on an upstream failure while no inbound byte arrived")
        void bodylessGetIsRetriedWhileNoInboundByteArrived() {
            GatewayException rejection = rejectionOf(dispatchGet(route(upstreamClient), FAILING_PATH,
                    new TestReadStream()));

            assertAll("a bodyless GET against a failing upstream",
                    () -> assertEquals(EventType.UPSTREAM_ERROR, rejection.getEventType()),
                    () -> assertEquals(3, upstreamStarted.get(),
                            "the inbound stream is never subscribed to an attempt, so 1 attempt + 2 retries run"));
        }

        @Test
        @DisplayName("a body byte before the upstream request is sent refuses the dispatch; nothing is sent")
        void bodyByteBeforeTheSendRefusesTheDispatch() {
            TestReadStream inbound = new TestReadStream();
            inbound.emitOnResume(Buffer.buffer(Generators.letterStrings(1, 8).next()));

            GatewayException rejection = rejectionOf(dispatchGet(route(upstreamClient), ANSWERING_PATH, inbound));

            assertAll("a body byte before the send",
                    () -> assertEquals(EventType.SECURITY_FILTER_VIOLATION, rejection.getEventType()),
                    () -> assertEquals(0, upstreamStarted.get(), "the refused request is never sent upstream"),
                    () -> assertEquals(List.of(), lateViolations));
        }

        @Test
        @DisplayName("a body byte while the response is awaited refuses the dispatch and is never retried")
        void bodyByteWhileTheResponseIsAwaitedRefusesTheDispatch() throws Exception {
            TestReadStream inbound = new TestReadStream();
            Future<HttpClientResponse> dispatched = dispatchGet(route(upstreamClient), HELD_PATH, inbound);
            Awaits.until(() -> upstreamStarted.get() == 1, "the upstream to receive the bodyless request",
                    Awaits.CONNECT_CEILING_SECONDS);

            inbound.emit(Buffer.buffer(Generators.letterStrings(1, 8).next()));
            GatewayException rejection = rejectionOf(dispatched);

            assertAll("a body byte while the response is awaited",
                    () -> assertEquals(EventType.SECURITY_FILTER_VIOLATION, rejection.getEventType()),
                    () -> assertEquals(1, upstreamStarted.get(), "a refused request is never retried"),
                    () -> assertEquals(0L, upstreamBodyBytes.get(), "no body byte may reach the upstream"),
                    () -> assertEquals(List.of(), lateViolations));
        }

        @Test
        @DisplayName("a body byte after the response arrived but before its hand-over refuses the dispatch")
        void bodyByteBeforeTheHandOverRefusesTheDispatch() {
            TestReadStream inbound = new TestReadStream();
            String chunk = Generators.letterStrings(1, 8).next();
            HttpClient client = interceptingClient(upstreamClient,
                    composed -> {
                        CompletableFuture<@Nullable Object> received = new CompletableFuture<>();
                        composed.onComplete(_ -> received.complete(null));
                        Awaits.connect(received, "the upstream response to be received");
                        inbound.emit(Buffer.buffer(chunk));
                    });

            GatewayException rejection = rejectionOf(dispatchGet(route(client), ANSWERING_PATH, inbound));

            assertAll("a body byte before the hand-over",
                    () -> assertEquals(EventType.SECURITY_FILTER_VIOLATION, rejection.getEventType(),
                            "the response is not relayed: the dispatch fails with the refusal"),
                    () -> assertEquals(List.of(), lateViolations,
                            "a refusal before the hand-over is the dispatch's own failure, not a late one"),
                    () -> assertEquals(0L, upstreamBodyBytes.get(), "no body byte may reach the upstream"));
        }

        /** A retry-enabled proxy route over {@code client} with a 1 + 2 retry guard, as the edge builds one. */
        private Routed route(HttpClient client) {
            return Routed.of("bodyless", new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstream.actualPort(), ""),
                    true, client,
                    Guard.create()
                            .withRetry().maxRetries(2).delay(0, ChronoUnit.MILLIS)
                            .whenException(DispatchStage::allowsRetryAfter).done()
                            .build(),
                    DispatchStage.NO_BODY_IDLE_TIMEOUT, new UpstreamFailureMapper(new GatewayEventCounter()));
        }

        /** Runs one bodyless GET dispatch on a virtual thread, as the edge does. */
        private Future<HttpClientResponse> dispatchGet(Routed routed, String path, ReadStream<Buffer> inbound) {
            return virtualThreadExecutor.submit(() -> routed.dispatch(HttpMethod.GET, path, Map.of(), inbound,
                    DispatchStage.BodyFraming.of(HttpMethod.GET, DispatchStage.NO_DECLARED_LENGTH,
                            lateViolations::add)));
        }
    }

    /**
     * An attempt the dispatch stops waiting for releases its upstream exchange: a request still
     * waiting for a pooled connection is never sent, a request in flight is reset, and a response
     * that was received and paused but never reached the caller is reset.
     * <p>
     * Every case runs over an upstream client holding at most one HTTP/1.1 connection, so the next
     * dispatch on the same client is served only once the attempt has been released. The attempt's
     * own route carries a guard with a short timeout; every follow-up dispatch runs on a route over
     * the same client whose guard leaves it ample time.
     */
    @Nested
    @DisplayName("release of an attempt the dispatch stopped waiting for")
    class AbandonedAttempt {

        /** The guard timeout of the attempt the dispatch stops waiting for. */
        private static final long ATTEMPT_TIMEOUT_MILLIS = 500L;
        /** The guard timeout of every follow-up dispatch, well inside the connect ceiling. */
        private static final long PATIENT_TIMEOUT_SECONDS = Awaits.CONNECT_CEILING_SECONDS / 2;
        /** The size of an upstream answer that is released unread. */
        private static final int LARGE_BODY_BYTES = 4 * 1024 * 1024;
        private static final String HELD_PATH = "/held";
        private static final String LARGE_PATH = "/large";
        private static final String ABANDONED_PATH = "/abandoned";
        private static final String ANSWERING_PATH = "/answering";
        private static final String DRAIN_PATH = "/drain";
        private static final String ANSWER = "ok";
        private static final String ANSWERED = "200 " + ANSWER;

        private Vertx vertx;
        private ExecutorService virtualThreadExecutor;
        private HttpServer upstream;
        private HttpClient upstreamClient;
        private Guard attemptGuard;
        private Guard patientGuard;
        /** Answers the request to the held path once completed with the body to answer it with. */
        private final Promise<Buffer> releaseHeld = Promise.promise();
        /** The path of every request the stub upstream received a head for, in arrival order. */
        private final List<String> receivedPaths = new CopyOnWriteArrayList<>();
        /** The path of the latest request on every upstream connection that was closed. */
        private final List<String> closedConnections = new CopyOnWriteArrayList<>();

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
            // The stub upstream answers the held path only once the test releases it, answers the
            // large path at once with a large body, and answers every other path once it ended.
            upstream = Awaits.connect(vertx.createHttpServer().requestHandler(request -> {
                String path = request.path();
                receivedPaths.add(path);
                request.connection().closeHandler(_ -> closedConnections.add(path));
                switch (path) {
                    case HELD_PATH -> releaseHeld.future().onSuccess(body -> {
                        if (!request.response().closed()) {
                            request.response().end(body);
                        }
                    });
                    case LARGE_PATH -> request.response().end(largeBody());
                    default -> request.endHandler(_ -> request.response().end(ANSWER));
                }
            }).listen(0, LoopbackHost.ADDRESS), "the stub upstream to start listening");
            upstreamClient = vertx.createHttpClient(new HttpClientOptions(), new PoolOptions().setHttp1MaxSize(1));
            attemptGuard = Guard.create()
                    .withTimeout().duration(ATTEMPT_TIMEOUT_MILLIS, ChronoUnit.MILLIS).done()
                    .build();
            patientGuard = Guard.create()
                    .withTimeout().duration(PATIENT_TIMEOUT_SECONDS, ChronoUnit.SECONDS).done()
                    .build();
        }

        @AfterEach
        void tearDown() throws Exception {
            releaseHeld.tryComplete(Buffer.buffer(ANSWER));
            Awaits.teardown(upstreamClient.close(), "the upstream client to close");
            Awaits.teardown(upstream.close(), "the stub upstream to close");
            virtualThreadExecutor.close();
            Awaits.teardown(vertx.close(), "Vert.x to close");
        }

        @Test
        @DisplayName("an attempt still awaiting its response head is released: its connection closes and the client serves the next dispatch")
        void attemptAwaitingItsResponseIsReleased() throws Exception {
            // Act — the dispatch stops waiting while the upstream still holds the response head back.
            GatewayException rejection = rejectionOf(dispatchGet(route(upstreamClient, attemptGuard), HELD_PATH));
            Awaits.until(() -> receivedPaths.contains(HELD_PATH), "the upstream to receive the held request",
                    Awaits.CONNECT_CEILING_SECONDS);
            releaseHeld.complete(largeBody());
            Awaits.until(() -> closedConnections.contains(HELD_PATH),
                    "the upstream to see the connection of the released attempt closed", Awaits.CONNECT_CEILING_SECONDS);
            String next = answerOf(dispatchGet(route(upstreamClient, patientGuard), ANSWERING_PATH));

            // Assert
            assertAll("an attempt the dispatch stopped waiting for while its response was outstanding",
                    () -> assertEquals(EventType.UPSTREAM_TIMEOUT, rejection.getEventType()),
                    () -> assertTrue(closedConnections.contains(HELD_PATH),
                            "the upstream connection of the released attempt must be closed"),
                    () -> assertEquals(ANSWERED, next,
                            "the single pooled connection must be free again for the next dispatch"));
        }

        @Test
        @DisplayName("an attempt still queued for a pooled connection is released and never sent upstream")
        void queuedAttemptIsNeverSent() throws Exception {
            // Arrange — a first dispatch occupies the single pooled connection.
            Future<HttpClientResponse> occupying = dispatchGet(route(upstreamClient, patientGuard), HELD_PATH);
            Awaits.until(() -> receivedPaths.contains(HELD_PATH), "the upstream to receive the occupying request",
                    Awaits.CONNECT_CEILING_SECONDS);

            // Act — the dispatch stops waiting for a second attempt queued behind it; then the first
            // completes and frees the connection, and a well-framed drain request follows on the client.
            GatewayException rejection = rejectionOf(dispatchGet(route(upstreamClient, attemptGuard), ABANDONED_PATH));
            releaseHeld.complete(Buffer.buffer(ANSWER));
            String occupied = answerOf(occupying);
            String drained = answerOf(dispatchGet(route(upstreamClient, patientGuard), DRAIN_PATH));

            // Assert
            assertAll("a queued attempt the dispatch stopped waiting for",
                    () -> assertEquals(EventType.UPSTREAM_TIMEOUT, rejection.getEventType()),
                    () -> assertEquals(ANSWERED, occupied, "the occupying dispatch is answered"),
                    () -> assertEquals(ANSWERED, drained, "the drain request behind it must be served"),
                    () -> assertFalse(receivedPaths.contains(ABANDONED_PATH),
                            () -> "the released attempt must never reach the upstream, but it received " + receivedPaths));
        }

        @Test
        @DisplayName("a paused response that never reached the caller is released: its connection closes and the client serves the next dispatch")
        void pausedResponseIsReleased() throws Exception {
            // Arrange — the dispatching thread is held until the response head was received and
            // paused, and past the attempt's timeout.
            PastTheTimeoutStall stall = new PastTheTimeoutStall();
            HttpClient stalling = interceptingClient(upstreamClient, stall::hold);

            // Act
            GatewayException rejection = rejectionOf(dispatchGet(route(stalling, attemptGuard), LARGE_PATH));
            Awaits.until(() -> closedConnections.contains(LARGE_PATH),
                    "the upstream to see the connection of the released response closed", Awaits.CONNECT_CEILING_SECONDS);
            String next = answerOf(dispatchGet(route(upstreamClient, patientGuard), ANSWERING_PATH));

            // Assert
            assertAll("a paused response the dispatch stopped waiting for",
                    () -> assertTrue(stall.heldPastHead(),
                            "the dispatching thread must have been held until the response head was received,"
                                    + " otherwise this run never reached the interleaving under test"),
                    () -> assertTrue(stall.heldPastTimeout(),
                            "the dispatching thread must have been held past the attempt's timeout,"
                                    + " otherwise this run never reached the interleaving under test"),
                    () -> assertEquals(EventType.UPSTREAM_TIMEOUT, rejection.getEventType()),
                    () -> assertTrue(closedConnections.contains(LARGE_PATH),
                            "the upstream connection of the released response must be closed"),
                    () -> assertEquals(ANSWERED, next,
                            "the single pooled connection must be free again for the next dispatch"));
        }

        /** A proxy route over {@code client}, without retry, guarded by {@code guard}. */
        private Routed route(HttpClient client, Guard guard) {
            return Routed.of("abandoned-attempt",
                    new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstream.actualPort(), ""), false, client, guard,
                    DispatchStage.NO_BODY_IDLE_TIMEOUT, new UpstreamFailureMapper(new GatewayEventCounter()));
        }

        /** Runs one bodyless GET dispatch on a virtual thread, as the edge does. */
        private Future<HttpClientResponse> dispatchGet(Routed routed, String path) {
            return virtualThreadExecutor.submit(() -> routed.dispatch(HttpMethod.GET, path, Map.of(),
                    new TestReadStream(), DispatchStage.BodyFraming.of(HttpMethod.GET, DispatchStage.NO_DECLARED_LENGTH,
                            _ -> {
                                // No body byte is ever sent in these cases, so no late refusal arrives.
                            })));
        }

        /**
         * Awaits a dispatch and reads its whole answer.
         *
         * @return the status and body, or a description of the failure when the dispatch was not answered
         */
        private String answerOf(Future<HttpClientResponse> dispatched) throws Exception {
            HttpClientResponse response;
            try {
                response = Awaits.connect(dispatched, "the dispatch to be answered");
            } catch (ExecutionException noAnswer) {
                return "no answer: " + noAnswer.getCause();
            }
            io.vertx.core.Future<Buffer> body = response.body();
            response.resume();
            return response.statusCode() + " " + Awaits.connect(body, "the upstream answer body to arrive");
        }

        private static Buffer largeBody() {
            return Buffer.buffer(new byte[LARGE_BODY_BYTES]);
        }
    }

    /**
     * Holds the dispatching thread, right after it chained the upstream send, until the composed
     * exchange has delivered its response head and the attempt's guard timeout has interrupted the
     * thread; it then returns normally with the interrupt status restored, so the attempt goes on to
     * return its response to a guard whose timeout has already fired.
     */
    private static final class PastTheTimeoutStall {

        private final AtomicBoolean heldPastHead = new AtomicBoolean();
        private final AtomicBoolean heldPastTimeout = new AtomicBoolean();

        void hold(io.vertx.core.Future<?> composed) {
            CompletableFuture<@Nullable Object> head = new CompletableFuture<>();
            composed.onComplete(result -> head.complete(result.result()));
            // join() is not interruptible: an interrupt that arrives while the head is outstanding is
            // kept as the thread's interrupt status rather than ending the wait.
            heldPastHead.set(head.completeOnTimeout(null, Awaits.CONNECT_CEILING_SECONDS, TimeUnit.SECONDS)
                    .join() instanceof HttpClientResponse);
            heldPastTimeout.set(Thread.currentThread().isInterrupted() || interruptedWithinConnectCeiling());
        }

        /**
         * Waits for the current thread to be interrupted, keeping its interrupt status set.
         *
         * @return whether the interrupt arrived within the connect ceiling
         */
        private static boolean interruptedWithinConnectCeiling() {
            try {
                // Nothing counts this latch down, so only an interrupt ends the wait early.
                return new CountDownLatch(1).await(Awaits.CONNECT_CEILING_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return true;
            }
        }

        boolean heldPastHead() {
            return heldPastHead.get();
        }

        boolean heldPastTimeout() {
            return heldPastTimeout.get();
        }
    }

    /** Awaits a dispatch that must fail and returns the {@link GatewayException} it raised. */
    private static GatewayException rejectionOf(Future<HttpClientResponse> dispatched) {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> Awaits.connect(dispatched, "the dispatch to be rejected"));
        return assertInstanceOf(GatewayException.class, failure.getCause());
    }

    /**
     * The guard's retry predicate, {@link DispatchStage#allowsRetryAfter(Throwable)}, decides whether a
     * failed attempt is retried. Every guarded case runs a 1 + 2 retry guard deciding through that
     * production predicate, so tests and production cannot drift apart.
     */
    @Nested
    @DisplayName("stream-aware retry gating on the guarded dispatch path")
    class RetryGating {

        /** A stage over an empty upstream lookup: the guarded path under test resolves no client itself. */
        private DispatchStage newStage() {
            return new DispatchStage(new RouteAssembler.UpstreamClients(Map.of(), Map.of()), BODY_CAP,
                    DispatchStage.NO_BODY_IDLE_TIMEOUT, new UpstreamFailureMapper(new GatewayEventCounter()));
        }

        /** A retry-enabled guard (1 + 2 retries) deciding through the production retry predicate. */
        private Guard retryGuard() {
            return Guard.create()
                    .withRetry().maxRetries(2).delay(0, ChronoUnit.MILLIS)
                    .whenException(DispatchStage::allowsRetryAfter).done()
                    .build();
        }

        @Test
        @DisplayName("the retry predicate refuses a retry outside a guarded dispatch")
        void predicateFailsClosedOutsideAGuardedDispatch() {
            assertFalse(DispatchStage.allowsRetryAfter(new IllegalStateException("upstream down")),
                    "without a dispatch's retry state the predicate must refuse the retry");
        }

        @Test
        @DisplayName("never retries a gateway rejection, even when the dispatch would otherwise allow it")
        void neverRetriesAGatewayRejection() {
            // Arrange — an idempotent GET with no body byte sent, whose attempt ends in a rejection
            DispatchStage stage = newStage();
            StreamAwareRetryGate gate = new StreamAwareRetryGate(true);
            AtomicInteger attempts = new AtomicInteger();
            AtomicLong bytesSent = new AtomicLong();
            Callable<HttpClientResponse> rejected = () -> {
                attempts.incrementAndGet();
                throw new GatewayException(EventType.CONTENT_TOO_LARGE, "Request body exceeded max_body_bytes");
            };

            // Act
            var guard = retryGuard();
            GatewayException raised = assertThrows(GatewayException.class,
                    () -> stage.guardedDispatch(guard, gate, HttpMethod.GET, bytesSent::get, () -> false,
                            rejected));

            // Assert — the rejection ends the dispatch unchanged after a single attempt
            assertAll("a gateway rejection",
                    () -> assertEquals(1, attempts.get(), "a gateway rejection must never be retried"),
                    () -> assertEquals(EventType.CONTENT_TOO_LARGE, raised.getEventType()));
        }

        @Test
        @DisplayName("retries an idempotent GET while no request body byte has been sent")
        void retriesIdempotentWithoutBody() {
            // Arrange — a failing upstream that never streams a body byte (fails before send)
            DispatchStage stage = newStage();
            StreamAwareRetryGate gate = new StreamAwareRetryGate(true);
            AtomicInteger attempts = new AtomicInteger();
            AtomicLong bytesSent = new AtomicLong();
            Callable<HttpClientResponse> failing = () -> {
                attempts.incrementAndGet();
                // NOSONAR java:S125 — explanatory prose about the ExecutionException-wrapping contract
                // (awaitDispatch surfaces upstream failures via Future#get; guardedDispatch must unwrap
                // it before mapping/retrying), NOT commented-out code — the throw below is live.
                throw new ExecutionException("upstream down", new IllegalStateException("upstream down"));
            };

            // Act — the stream is never subscribed (failure before send), so retry is allowed
            var guard = retryGuard();
            GatewayException raised = assertThrows(GatewayException.class,
                    () -> stage.guardedDispatch(guard, gate, HttpMethod.GET, bytesSent::get, () -> false,
                            failing));

            // Assert — the safe idempotent+bodyless request was retried the full budget
            assertEquals(3, attempts.get(),
                    "an idempotent GET with zero body bytes must be retried (1 attempt + 2 retries)");
            assertEquals(EventType.UPSTREAM_ERROR, raised.getEventType());
        }

        @Test
        @DisplayName("never retries a non-idempotent POST — the retry is refused when the attempt fails")
        void neverRetriesPost() {
            // Arrange
            DispatchStage stage = newStage();
            StreamAwareRetryGate gate = new StreamAwareRetryGate(true);
            AtomicInteger attempts = new AtomicInteger();
            AtomicLong bytesSent = new AtomicLong();
            Callable<HttpClientResponse> failing = () -> {
                attempts.incrementAndGet();
                throw new ExecutionException("upstream down", new IllegalStateException("upstream down"));
            };

            // Act
            var guard = retryGuard();
            assertThrows(GatewayException.class,
                    () -> stage.guardedDispatch(guard, gate, HttpMethod.POST, bytesSent::get, () -> false,
                            failing));

            // Assert — a POST must never be re-sent, so the upstream is called exactly once
            assertEquals(1, attempts.get(), "a POST must never be re-sent to the upstream");
        }

        @Test
        @DisplayName("never retries an idempotent request once a body byte has crossed to the upstream")
        void neverRetriesAfterBodyByte() {
            // Arrange — the first attempt streams body bytes before it fails mid-body
            DispatchStage stage = newStage();
            StreamAwareRetryGate gate = new StreamAwareRetryGate(true);
            AtomicInteger attempts = new AtomicInteger();
            AtomicLong bytesSent = new AtomicLong();
            Callable<HttpClientResponse> failing = () -> {
                attempts.incrementAndGet();
                bytesSent.addAndGet(5L);
                throw new ExecutionException("upstream down mid-body",
                        new IllegalStateException("upstream down mid-body"));
            };

            // Act
            var guard = retryGuard();
            assertThrows(GatewayException.class,
                    () -> stage.guardedDispatch(guard, gate, HttpMethod.PUT, bytesSent::get, () -> false,
                            failing));

            // Assert — a streamed request cannot be replayed once a body byte has been sent
            assertEquals(1, attempts.get(),
                    "an idempotent request whose body has streamed a byte must never be retried");
        }

        @Test
        @DisplayName("never retries when the one-shot body stream was already subscribed, even with zero bytes sent")
        void neverRetriesWhenBodyStreamAlreadySubscribed() {
            // Arrange — the first attempt reached request.send(...) and subscribed the single-use
            // request-body stream before failing, even though no body byte was counted yet. A retry
            // would re-attach the already-consumed stream and stall, so it must fail explicitly.
            DispatchStage stage = newStage();
            StreamAwareRetryGate gate = new StreamAwareRetryGate(true);
            AtomicInteger attempts = new AtomicInteger();
            AtomicLong bytesSent = new AtomicLong();
            Callable<HttpClientResponse> failing = () -> {
                attempts.incrementAndGet();
                throw new ExecutionException("upstream reset after subscribe",
                        new IllegalStateException("upstream down"));
            };

            // Act — idempotent GET, zero bytes sent, but the body stream is already subscribed
            var guard = retryGuard();
            assertThrows(GatewayException.class,
                    () -> stage.guardedDispatch(guard, gate, HttpMethod.GET, bytesSent::get, () -> true,
                            failing));

            // Assert — reusing an already-subscribed one-shot body stream is refused, no re-send
            assertEquals(1, attempts.get(),
                    "a retry that would reuse an already-subscribed one-shot body stream must be refused");
        }
    }

    /**
     * The shared client and guard are resolved from the boot-built lookup by the route's upstream
     * target and resilience shape; a route the lookup cannot serve is a boot-assembly defect and is
     * refused before anything is sent.
     */
    @Nested
    @DisplayName("shared client and guard resolved from the upstream lookup")
    class UpstreamLookup {

        private static final ResolvedUpstream UPSTREAM = new ResolvedUpstream("http", "orders-svc", 8080, "");

        @Test
        @DisplayName("refuses a route that resolves no proxy upstream")
        void refusesARouteWithoutUpstream() {
            Route assetRoute = Route.builder()
                    .id("asset")
                    .match(MatchConfig.builder().pathPrefix("/asset").build())
                    .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                    .asset(ResolvedAsset.directory("/srv/assets", AccessLevel.PUBLIC, null, null))
                    .build();
            DispatchStage stage = stageOver(Map.of(), Map.of());

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> dispatchGet(stage, assetRoute));

            assertEquals("proxy dispatch requires a resolved upstream", thrown.getMessage());
        }

        @Test
        @DisplayName("refuses a route whose upstream target the lookup holds no client for")
        void refusesARouteWithoutClient() {
            Route route = proxyRoute();
            DispatchStage stage = stageOver(Map.of(), Map.of());

            IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> dispatchGet(stage, route));

            assertEquals("proxy dispatch requires an upstream client", thrown.getMessage());
        }

        @Test
        @DisplayName("refuses a route whose resilience shape the lookup holds no guard for")
        void refusesARouteWithoutGuard() {
            Route route = proxyRoute();
            DispatchStage stage = stageOver(Map.of(RouteAssembler.UpstreamTarget.of(route), unusedClient()), Map.of());

            IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> dispatchGet(stage, route));

            assertEquals("proxy dispatch requires a resilience guard", thrown.getMessage());
        }

        private Route proxyRoute() {
            return Route.builder()
                    .id("orders")
                    .match(MatchConfig.builder().pathPrefix("/orders").build())
                    .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                    .upstream(UPSTREAM)
                    .build();
        }

        private DispatchStage stageOver(Map<RouteAssembler.UpstreamTarget, HttpClient> clients,
                Map<RouteAssembler.ResilienceShape, Guard> guards) {
            return new DispatchStage(new RouteAssembler.UpstreamClients(clients, guards), BODY_CAP,
                    DispatchStage.NO_BODY_IDLE_TIMEOUT, new UpstreamFailureMapper(new GatewayEventCounter()));
        }

        private HttpClientResponse dispatchGet(DispatchStage stage, Route route) {
            return stage.dispatch(route, HttpMethod.GET, "/", Map.of(), new TestReadStream(),
                    DispatchStage.BodyFraming.of(HttpMethod.GET, DispatchStage.NO_DECLARED_LENGTH, _ -> {
                        // The dispatch is refused before anything is sent, so no late refusal arrives.
                    }));
        }

        /** A client the refused dispatch never reaches: every call on it fails the test. */
        private HttpClient unusedClient() {
            return (HttpClient) Proxy.newProxyInstance(HttpClient.class.getClassLoader(),
                    new Class<?>[]{HttpClient.class}, (_, method, _) -> {
                        throw new AssertionError("the refused dispatch must not touch the client: " + method.getName());
                    });
        }
    }

    @Nested
    @DisplayName("circuit-breaker attribution over a live upstream")
    class BreakerAttribution {

        /** The breaker's rolling window: this many failed calls in a row open it. */
        private static final int REQUEST_VOLUME_THRESHOLD = 4;
        private static final String ACCEPTING_PATH = "/accepting";
        private static final String FAILING_PATH = "/failing";
        /** The path the stub upstream answers in full at once, before it reads the request body. */
        private static final String EARLY_PATH = "/early";

        private Vertx vertx;
        private ExecutorService virtualThreadExecutor;
        private HttpServer upstream;
        private Guard guard;
        private Route route;
        private DispatchStage stage;
        /** The body the stub upstream answers a request to the early path with. */
        private String earlyAnswer;
        /** Every request the stub upstream received a head for. */
        private final AtomicInteger upstreamStarted = new AtomicInteger();
        /** Every request to the early path whose body failed at the stub upstream — the gateway reset it. */
        private final AtomicInteger earlyRequestsReset = new AtomicInteger();
        /** Every state the route's breaker moved to, in order. */
        private final List<CircuitBreakerState> breakerTransitions = new CopyOnWriteArrayList<>();

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
            earlyAnswer = Generators.letterStrings(8, 16).next();
            // The stub upstream answers a request once its body has arrived whole, drops the
            // connection of any request to the failing path as soon as its head arrives, and answers a
            // request to the early path in full at once, before reading its body.
            upstream = Awaits.connect(vertx.createHttpServer().requestHandler(request -> {
                upstreamStarted.incrementAndGet();
                if (FAILING_PATH.equals(request.path())) {
                    request.connection().close();
                    return;
                }
                if (EARLY_PATH.equals(request.path())) {
                    AtomicBoolean reset = new AtomicBoolean();
                    request.handler(_ -> {
                        // Read and discard: the body is observed only by whether it fails.
                    });
                    request.exceptionHandler(_ -> {
                        if (reset.compareAndSet(false, true)) {
                            earlyRequestsReset.incrementAndGet();
                        }
                    });
                    request.response().end(earlyAnswer);
                    return;
                }
                request.body().onSuccess(body -> request.response().end("ok"));
            }).listen(0, LoopbackHost.ADDRESS), "the stub upstream to start listening");

            // The breaker rules of the production guard (GatewayEdgeRoute#guardFor), with a window
            // small enough to fill within one test.
            guard = Guard.create()
                    .withCircuitBreaker()
                    .requestVolumeThreshold(REQUEST_VOLUME_THRESHOLD)
                    .failureRatio(0.5)
                    .delay(1, ChronoUnit.MINUTES)
                    .skipOn(GatewayException.class)
                    .onStateChange(breakerTransitions::add)
                    .done()
                    .build();
            RouteTable table = new RouteTable(List.of(Route.builder()
                    .id("upload")
                    .protocol(Protocol.HTTP)
                    .match(MatchConfig.builder().pathPrefix("/upload").build())
                    .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                    .effectiveAllowedMethods(Set.of(HttpMethod.POST))
                    .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstream.actualPort(), ""))
                    .build()));
            RouteAssembler.AssembledRoutes assembled = new RouteAssembler(new ProtocolProcessorRegistry()).assemble(
                    table,
                    _ -> new RouteAssembler.SecurityPosture(SecurityProfile.STRICT,
                            SecurityConfiguration.builder().build()),
                    _ -> vertx.createHttpClient(),
                    _ -> guard,
                    _ -> {
                        throw new UnsupportedOperationException("no asset route in this test");
                    });
            route = assembled.routes().getFirst();
            stage = new DispatchStage(assembled.upstreamClients(), BODY_CAP, DispatchStage.NO_BODY_IDLE_TIMEOUT,
                    new UpstreamFailureMapper(new GatewayEventCounter()));
        }

        @AfterEach
        void tearDown() throws Exception {
            Awaits.teardown(upstream.close(), "the stub upstream to close");
            virtualThreadExecutor.close();
            Awaits.teardown(vertx.close(), "Vert.x to close");
        }

        @Test
        @DisplayName("an inbound body that fails after dispatch began never moves the breaker toward open")
        void inboundFailureLeavesTheBreakerClosed() throws Exception {
            // Act — a full breaker window of dispatches, each ended by its own inbound body failing
            // after the upstream has received the request head.
            List<EventType> raised = new ArrayList<>();
            for (int call = 1; call <= REQUEST_VOLUME_THRESHOLD; call++) {
                TestReadStream inbound = new TestReadStream();
                Future<HttpClientResponse> dispatched = dispatch(ACCEPTING_PATH, inbound);
                sendFirstChunk(inbound, call);
                inbound.fail(new IllegalStateException("inbound body failed"));
                raised.add(rejectionOf(dispatched).getEventType());
            }
            // A dispatch behind that window still reaches the upstream and is answered.
            TestReadStream healthy = new TestReadStream();
            Future<HttpClientResponse> answered = dispatch(ACCEPTING_PATH, healthy);
            sendFirstChunk(healthy, REQUEST_VOLUME_THRESHOLD + 1);
            healthy.end();
            HttpClientResponse response = Awaits.connect(answered, "the dispatch behind the window to be answered");

            // Assert
            assertAll("client-caused inbound failures",
                    () -> assertEquals(Collections.nCopies(REQUEST_VOLUME_THRESHOLD, EventType.INBOUND_BODY_ABORTED),
                            raised, "each failed inbound body must surface as the client-attributed rejection"),
                    () -> assertEquals(List.of(), breakerTransitions,
                            "the breaker must stay closed through a full window of inbound failures"),
                    () -> assertEquals(200, response.statusCode(),
                            "the upstream must still be called once the window is full"));
        }

        /**
         * Dispatches the upstream answers in full before reading their bodies, each upstream request
         * then reset by the gateway itself — {@link DispatchStage.InboundBody#resetByGateway()} on the
         * armed body, as the edge does once the answer is decided — twice the breaker's window over.
         * The guarded call returned as soon as the response head was handed back, so the reset falls
         * outside it: the breaker never sees it and stays closed, and an ordinary dispatch behind
         * those resets still reaches the upstream.
         */
        @Test
        @DisplayName("a gateway reset of the upstream request after a complete response never moves the breaker toward open")
        void gatewayResetAfterACompleteResponseLeavesTheBreakerClosed() throws Exception {
            int resets = 2 * REQUEST_VOLUME_THRESHOLD;
            List<String> answers = new ArrayList<>();
            for (int call = 1; call <= resets; call++) {
                TestReadStream source = new TestReadStream();
                DispatchStage.InboundBody inbound = DispatchStage.InboundBody.arm(source);
                Future<HttpClientResponse> dispatched = dispatch(EARLY_PATH, inbound);
                sendFirstChunk(source, call);
                HttpClientResponse response = Awaits.connect(dispatched, "the early answer to be handed back");
                io.vertx.core.Future<Buffer> body = response.body();
                response.resume();
                answers.add(Awaits.connect(body, "the early answer to arrive whole").toString());
                inbound.resetByGateway();
            }
            Awaits.until(() -> earlyRequestsReset.get() == resets,
                    "the upstream to observe every gateway reset", Awaits.CONNECT_CEILING_SECONDS);
            TestReadStream ordinary = new TestReadStream();
            Future<HttpClientResponse> answered = dispatch(ACCEPTING_PATH, ordinary);
            sendFirstChunk(ordinary, resets + 1);
            ordinary.end();
            HttpClientResponse response = Awaits.connect(answered, "the dispatch behind the resets to be answered");

            assertAll("gateway resets after complete responses",
                    () -> assertEquals(Collections.nCopies(resets, earlyAnswer), answers,
                            "every early answer must have been handed back whole before its reset"),
                    () -> assertEquals(resets, earlyRequestsReset.get(),
                            "every upstream request must have been reset by the gateway"),
                    () -> assertEquals(List.of(), breakerTransitions,
                            "a gateway reset after a complete response must not move the breaker"),
                    () -> assertEquals(200, response.statusCode(),
                            "the upstream must still be called behind the resets"));
        }

        @Test
        @DisplayName("a mid-upload inbound failure raises the dedicated event, never the security-filter violation")
        void inboundFailureIsNotASecurityFilterViolation() throws Exception {
            // Arrange
            TestReadStream inbound = new TestReadStream();
            Future<HttpClientResponse> dispatched = dispatch(ACCEPTING_PATH, inbound);
            sendFirstChunk(inbound, 1);

            // Act — the client goes away mid-upload
            inbound.fail(new IllegalStateException("client-chosen-text"));
            GatewayException rejection = rejectionOf(dispatched);

            // Assert
            assertAll("a failed inbound body",
                    () -> assertEquals(EventType.INBOUND_BODY_ABORTED, rejection.getEventType(),
                            "a failed inbound body must surface as its own client-attributed event"),
                    () -> assertNotEquals(EventType.SECURITY_FILTER_VIOLATION, rejection.getEventType(),
                            "a dropped upload is not a security filter violation"),
                    () -> assertEquals(400, rejection.getEventType().httpStatus()),
                    () -> assertFalse(rejection.getMessage().contains("client-chosen-text"),
                            "the disposition is fixed and carries nothing from the failure itself"));
        }

        @Test
        @DisplayName("a body crossing the cap mid-upload still surfaces as CONTENT_TOO_LARGE and leaves the breaker closed")
        void capBreachKeepsContentTooLarge() throws Exception {
            // Arrange — the stage caps the body at 1024 bytes
            TestReadStream inbound = new TestReadStream();
            Future<HttpClientResponse> dispatched = dispatch(ACCEPTING_PATH, inbound);
            sendFirstChunk(inbound, 1);

            // Act — one chunk that crosses the cap
            inbound.emit(Buffer.buffer(new byte[2048]));
            GatewayException rejection = rejectionOf(dispatched);

            // Assert
            assertAll("a body-cap breach",
                    () -> assertEquals(EventType.CONTENT_TOO_LARGE, rejection.getEventType(),
                            "the cap breach keeps its own event"),
                    () -> assertEquals(List.of(), breakerTransitions,
                            "a cap breach is client-caused and must not move the breaker"));
        }

        @Test
        @DisplayName("an upstream that drops the request is still counted and opens the breaker")
        void upstreamFailureStillOpensTheBreaker() throws Exception {
            // Act — a full breaker window of dispatches the upstream itself fails.
            List<EventType> raised = new ArrayList<>();
            for (int call = 1; call <= REQUEST_VOLUME_THRESHOLD; call++) {
                TestReadStream inbound = new TestReadStream();
                Future<HttpClientResponse> dispatched = dispatch(FAILING_PATH, inbound);
                sendFirstChunk(inbound, call);
                raised.add(rejectionOf(dispatched).getEventType());
            }
            EventType behindTheWindow = rejectionOf(dispatch(FAILING_PATH, new TestReadStream())).getEventType();

            // Assert
            assertAll("genuine upstream failures",
                    () -> assertEquals(Collections.nCopies(REQUEST_VOLUME_THRESHOLD, EventType.UPSTREAM_ERROR), raised,
                            "each dropped request must surface as an upstream failure"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN), breakerTransitions,
                            "a full window of upstream failures must open the breaker"),
                    () -> assertEquals(EventType.UPSTREAM_CIRCUIT_OPEN, behindTheWindow,
                            "a dispatch behind the window must be refused by the open breaker"),
                    () -> assertEquals(REQUEST_VOLUME_THRESHOLD, upstreamStarted.get(),
                            "the open breaker must not call the upstream"));
        }

        @Test
        @DisplayName("an inbound failure that follows an upstream failure leaves the failure attributed to the upstream")
        void inboundFailureAfterAnUpstreamFailureKeepsTheUpstreamAttribution() {
            TestReadStream inbound = new TestReadStream();
            // The upstream drops the request; only once the dispatch has seen that failure does the
            // inbound stream fail — before the dispatching thread goes on to read the outcome.
            Routed intercepted = Routed.of("upload",
                    new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstream.actualPort(), ""), false,
                    interceptingClient(vertx.createHttpClient(), composed -> {
                        sendFirstChunk(inbound, 1);
                        CompletableFuture<@Nullable Object> settled = new CompletableFuture<>();
                        composed.onComplete(_ -> settled.complete(null));
                        Awaits.connect(settled, "the upstream to fail the request");
                        inbound.fail(new IllegalStateException("inbound body failed"));
                    }),
                    guard, DispatchStage.NO_BODY_IDLE_TIMEOUT, new UpstreamFailureMapper(new GatewayEventCounter()));

            GatewayException rejection = rejectionOf(virtualThreadExecutor.submit(() -> intercepted.dispatch(
                    HttpMethod.POST, FAILING_PATH, Map.of(), inbound,
                    DispatchStage.BodyFraming.streamed(DispatchStage.NO_DECLARED_LENGTH))));

            assertAll("an upstream failure followed by an inbound failure",
                    () -> assertEquals(EventType.UPSTREAM_ERROR, rejection.getEventType(),
                            "the upstream failed first, so the dispatch reports the upstream failure"),
                    () -> assertEquals(1, upstreamStarted.get()));
        }

        /** Runs one dispatch on a virtual thread, as the edge does, so the test thread can drive its body. */
        private Future<HttpClientResponse> dispatch(String path, ReadStream<Buffer> inbound) {
            return virtualThreadExecutor.submit(() -> stage.dispatch(route, HttpMethod.POST, path, Map.of(), inbound,
                    DispatchStage.BodyFraming.streamed(DispatchStage.NO_DECLARED_LENGTH)));
        }

        /**
         * Sends the first body chunk once the dispatch has subscribed to the body, then waits until
         * the upstream has received the head of that request — its {@code call}-th.
         */
        private void sendFirstChunk(TestReadStream inbound, int call) throws Exception {
            Awaits.until(inbound::subscribed, "the dispatch to subscribe to the request body",
                    Awaits.CONNECT_CEILING_SECONDS);
            inbound.emit(Buffer.buffer("chunk"));
            Awaits.until(() -> upstreamStarted.get() == call, "the upstream to receive the request head",
                    Awaits.CONNECT_CEILING_SECONDS);
        }
    }

    /**
     * What the circuit breaker records for a dispatch whose retry is not taken. The guard has the
     * production shape — a breaker skipping {@link GatewayException}, a timeout inside it and a
     * 1 + 2 retry outside it, deciding through the production retry predicate
     * {@link DispatchStage#allowsRetryAfter(Throwable)} — over a retry-enabled route, and every
     * breaker outcome is counted through the breaker's own success and failure callbacks.
     */
    @Nested
    @DisplayName("circuit-breaker accounting of a vetoed retry")
    class VetoedRetryAccounting {

        /** The attempt timeout, well below the stub upstream's never-arriving answer. */
        private static final long ATTEMPT_TIMEOUT_MILLIS = 300L;
        private static final String HELD_PATH = "/held";

        private Vertx vertx;
        private ExecutorService virtualThreadExecutor;
        private HttpServer upstream;
        private HttpClient upstreamClient;
        private Guard guard;
        private final AtomicInteger breakerSuccesses = new AtomicInteger();
        private final AtomicInteger breakerFailures = new AtomicInteger();

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
            // The stub upstream accepts every request and never answers it.
            upstream = Awaits.connect(vertx.createHttpServer().requestHandler(_ -> {
                // Received, never answered.
            }).listen(0, LoopbackHost.ADDRESS), "the stub upstream to start listening");
            upstreamClient = vertx.createHttpClient();
            guard = Guard.create()
                    .withCircuitBreaker()
                    .requestVolumeThreshold(20)
                    .failureRatio(0.5)
                    .delay(1, ChronoUnit.MINUTES)
                    .skipOn(GatewayException.class)
                    .onSuccess(breakerSuccesses::incrementAndGet)
                    .onFailure(breakerFailures::incrementAndGet)
                    .done()
                    .withTimeout().duration(ATTEMPT_TIMEOUT_MILLIS, ChronoUnit.MILLIS).done()
                    .withRetry().maxRetries(2).delay(0, ChronoUnit.MILLIS)
                    .whenException(DispatchStage::allowsRetryAfter).done()
                    .build();
        }

        @AfterEach
        void tearDown() throws Exception {
            Awaits.teardown(upstreamClient.close(), "the upstream client to close");
            Awaits.teardown(upstream.close(), "the stub upstream to close");
            virtualThreadExecutor.close();
            Awaits.teardown(vertx.close(), "Vert.x to close");
        }

        @Test
        @DisplayName("a vetoed retry is not recorded by the circuit breaker: an upstream failure counts once, as a failure")
        void vetoedRetryAfterAnUpstreamFailureIsNotRecordedByTheBreaker() throws Exception {
            Routed unreachable = route(UnreachablePort.pick());

            GatewayException rejection = rejectionOf(dispatchPost(unreachable));

            assertAll("a POST whose upstream could not be reached",
                    () -> assertEquals(EventType.UPSTREAM_ERROR, rejection.getEventType()),
                    () -> assertEquals(1, breakerFailures.get(), "the upstream failure is recorded exactly once"),
                    () -> assertEquals(0, breakerSuccesses.get(),
                            "the retry that is not taken must not be recorded by the breaker at all"));
        }

        @Test
        @DisplayName("a vetoed retry is not recorded by the circuit breaker: an attempt timeout counts once, as a failure")
        void vetoedRetryAfterAnAttemptTimeoutIsNotRecordedByTheBreaker() {
            Routed unanswering = route(upstream.actualPort());

            GatewayException rejection = rejectionOf(dispatchPost(unanswering));

            assertAll("a POST whose attempt timed out",
                    () -> assertEquals(EventType.UPSTREAM_TIMEOUT, rejection.getEventType()),
                    () -> assertEquals(1, breakerFailures.get(), "the attempt timeout is recorded exactly once"),
                    () -> assertEquals(0, breakerSuccesses.get(),
                            "the retry that is not taken must not be recorded by the breaker at all"));
        }

        /** A retry-enabled proxy route to {@code port} on loopback, guarded by the production-shaped guard. */
        private Routed route(int port) {
            return Routed.of("vetoed-retry", new ResolvedUpstream("http", LoopbackHost.ADDRESS, port, ""), true,
                    upstreamClient, guard, DispatchStage.NO_BODY_IDLE_TIMEOUT,
                    new UpstreamFailureMapper(new GatewayEventCounter()));
        }

        /** Runs one streamed POST dispatch to the held path on a virtual thread, as the edge does. */
        private Future<HttpClientResponse> dispatchPost(Routed routed) {
            return virtualThreadExecutor.submit(() -> routed.dispatch(HttpMethod.POST, HELD_PATH, Map.of(),
                    new TestReadStream(), DispatchStage.BodyFraming.streamed(DispatchStage.NO_DECLARED_LENGTH)));
        }
    }

    /**
     * The request-body idle timeout over a live upstream: a body that stops arriving while its dispatch
     * holds a pooled upstream connection ends the dispatch as {@link EventType#REQUEST_BODY_TIMEOUT} and
     * frees the connection at once, while a body that keeps arriving, however slowly, is never cut off.
     * <p>
     * The upstream client holds at most one HTTP/1.1 connection, so a follow-up dispatch on the same
     * client is served only once the idle dispatch released it. Every route's guard leaves the dispatch
     * far more time than the idle timeout, so without the idle clock an idle dispatch would hold the
     * connection until that guard timed it out as an upstream timeout.
     */
    @Nested
    @DisplayName("request-body idle timeout over a live upstream")
    class BodyIdleRelease {

        /** The idle timeout of the release cases: short, so a case observes it without waiting long. */
        private static final long IDLE_TIMEOUT_MILLIS = 300L;
        /** The idle timeout of the slow-and-steady case: ten times the gap between its chunks. */
        private static final long STEADY_TIMEOUT_MILLIS = 1_000L;
        private static final long STEADY_CHUNK_GAP_MILLIS = 100L;
        /** Chunks the slow-and-steady upload sends, one per gap: three idle timeouts' worth of upload. */
        private static final int STEADY_CHUNKS = 30;
        /** The production breaker's request-volume threshold (GatewayEdgeRoute#guardFor). */
        private static final int REQUEST_VOLUME_THRESHOLD = 20;
        private static final long PATIENT_TIMEOUT_SECONDS = Awaits.CONNECT_CEILING_SECONDS / 2;
        private static final String ANSWERING_PATH = "/answering";
        private static final String ANSWER = "ok";

        private Vertx vertx;
        private ExecutorService virtualThreadExecutor;
        private HttpServer upstream;
        private HttpClient upstreamClient;
        private final UpstreamFailureMapper failureMapper = new UpstreamFailureMapper(new GatewayEventCounter());
        /** Every connection the stub upstream accepted. */
        private final AtomicInteger acceptedConnections = new AtomicInteger();
        /** The path of every request the stub upstream received a head for, in arrival order. */
        private final List<String> receivedPaths = new CopyOnWriteArrayList<>();
        /** The size of every request body the stub upstream read whole, by path. */
        private final Map<String, Integer> receivedBodyBytes = new ConcurrentHashMap<>();
        /** Every state a breaker of these cases moved to, in order. */
        private final List<CircuitBreakerState> breakerTransitions = new CopyOnWriteArrayList<>();

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
            // The stub upstream answers every request once its body has arrived whole. It is HTTP/1.1
            // only: without h2c to detect, it reports a connection when it is accepted rather than with
            // its first bytes, and a request whose body never arrives sends none.
            upstream = Awaits.connect(vertx.createHttpServer(new HttpServerOptions().setHttp2ClearTextEnabled(false))
                    .connectionHandler(_ -> acceptedConnections.incrementAndGet())
                    .requestHandler(request -> {
                        receivedPaths.add(request.path());
                        request.body().onSuccess(body -> {
                            receivedBodyBytes.put(request.path(), body.length());
                            request.response().end(ANSWER);
                        });
                    }).listen(0, LoopbackHost.ADDRESS), "the stub upstream to start listening");
            upstreamClient = vertx.createHttpClient(new HttpClientOptions(), new PoolOptions().setHttp1MaxSize(1));
        }

        @AfterEach
        void tearDown() throws Exception {
            Awaits.teardown(upstreamClient.close(), "the upstream client to close");
            Awaits.teardown(upstream.close(), "the stub upstream to close");
            virtualThreadExecutor.close();
            Awaits.teardown(vertx.close(), "Vert.x to close");
        }

        @Test
        @DisplayName("a body that never arrives releases the pool's only connection, which the next dispatch obtains")
        void idleBodyReleasesTheConnectionForTheNextDispatch() throws Exception {
            Routed routed = routed(IDLE_TIMEOUT_MILLIS, patientGuard(), false);

            GatewayException rejection = rejectionOf(dispatch(routed, HttpMethod.POST, "/idle",
                    new TestReadStream()));
            String next = answerOf(dispatch(routed, HttpMethod.GET, ANSWERING_PATH, new TestReadStream()));

            // The idle request never wrote its head — the client writes it with the first body chunk — so
            // the reset leaves its connection reusable: the pool hands the same connection on.
            assertAll("a dispatch whose body never arrives",
                    () -> assertEquals(EventType.REQUEST_BODY_TIMEOUT, rejection.getEventType(),
                            "the idle body ends the dispatch as the request's, not as an upstream timeout"),
                    () -> assertEquals("200 " + ANSWER, next,
                            "the pool's only connection must be free again for the next dispatch"),
                    () -> assertEquals(List.of(ANSWERING_PATH), receivedPaths,
                            "the idle request never sent its head; only the next dispatch reached the upstream"),
                    () -> assertEquals(1, acceptedConnections.get(),
                            "the next dispatch obtains the connection the idle dispatch released"));
        }

        @Test
        @DisplayName("a slow and steady upload is never cut off: answered 200, its whole body received")
        void slowSteadyUploadIsNotCutOff() throws Exception {
            TestReadStream inbound = new TestReadStream();
            String chunk = Generators.letterStrings(4, 4).next();

            Future<HttpClientResponse> dispatched = dispatch(routed(STEADY_TIMEOUT_MILLIS, patientGuard(), false),
                    HttpMethod.POST, "/steady", inbound);
            Awaits.until(inbound::subscribed, "the dispatch to subscribe to the request body",
                    Awaits.CONNECT_CEILING_SECONDS);
            AtomicInteger sent = new AtomicInteger();
            vertx.setPeriodic(STEADY_CHUNK_GAP_MILLIS, timerId -> {
                if (sent.incrementAndGet() <= STEADY_CHUNKS) {
                    inbound.emit(Buffer.buffer(chunk));
                } else {
                    vertx.cancelTimer(timerId);
                    inbound.end();
                }
            });
            String answer = answerOf(dispatched);

            assertAll("a slow and steady upload",
                    () -> assertEquals("200 " + ANSWER, answer,
                            "a body that keeps arriving within the timeout is never cut off"),
                    () -> assertEquals(Integer.valueOf(STEADY_CHUNKS * chunk.length()), receivedBodyBytes.get("/steady"),
                            "the upstream must receive the whole body"));
        }

        @Test
        @DisplayName("more idle expiries than the breaker's window leave it closed, and the next dispatch reaches the upstream")
        void idleExpiriesLeaveTheBreakerClosed() throws Exception {
            Routed routed = routed(IDLE_TIMEOUT_MILLIS, breakerGuard(), false);
            List<EventType> raised = new ArrayList<>();

            for (int call = 0; call <= REQUEST_VOLUME_THRESHOLD; call++) {
                raised.add(rejectionOf(dispatch(routed, HttpMethod.POST, "/idle-" + call,
                        new TestReadStream())).getEventType());
            }
            String next = answerOf(dispatch(routed, HttpMethod.GET, ANSWERING_PATH, new TestReadStream()));

            assertAll("idle expiries beyond the breaker's window",
                    () -> assertEquals(Collections.nCopies(REQUEST_VOLUME_THRESHOLD + 1, EventType.REQUEST_BODY_TIMEOUT),
                            raised, "every idle body is the request's, never an upstream failure"),
                    () -> assertEquals(List.of(), breakerTransitions, "the breaker must stay closed"),
                    () -> assertEquals("200 " + ANSWER, next, "the next dispatch must reach the upstream"),
                    () -> assertEquals(List.of(ANSWERING_PATH), receivedPaths));
        }

        @Test
        @DisplayName("an idle expiry on a retry-enabled route is not retried: the upstream sees one connection")
        void idleExpiryIsNotRetried() {
            Guard retryGuard = Guard.create()
                    .withTimeout().duration(PATIENT_TIMEOUT_SECONDS, ChronoUnit.SECONDS).done()
                    .withRetry().maxRetries(2).delay(0, ChronoUnit.MILLIS)
                    .whenException(DispatchStage::allowsRetryAfter).done()
                    .build();

            AtomicInteger attempts = new AtomicInteger();
            HttpClient counting = interceptingClient(upstreamClient, _ -> attempts.incrementAndGet());

            // An idempotent PUT, so only the expiry itself can keep the guard from retrying.
            GatewayException rejection = rejectionOf(dispatch(routed(IDLE_TIMEOUT_MILLIS, counting, retryGuard, true),
                    HttpMethod.PUT, "/idle-retry", new TestReadStream()));

            assertAll("an idle expiry on a retry-enabled route",
                    () -> assertEquals(EventType.REQUEST_BODY_TIMEOUT, rejection.getEventType()),
                    () -> assertEquals(1, attempts.get(), "an idle expiry must never be retried"),
                    () -> assertEquals(1, acceptedConnections.get(), "the upstream saw exactly one connection"));
        }

        /** A guard whose timeout leaves the dispatch far more time than any idle timeout of these cases. */
        private Guard patientGuard() {
            return Guard.create()
                    .withTimeout().duration(PATIENT_TIMEOUT_SECONDS, ChronoUnit.SECONDS).done()
                    .build();
        }

        /** The breaker rules of the production guard (GatewayEdgeRoute#guardFor) inside a patient timeout. */
        private Guard breakerGuard() {
            return Guard.create()
                    .withCircuitBreaker()
                    .requestVolumeThreshold(REQUEST_VOLUME_THRESHOLD)
                    .failureRatio(0.5)
                    .delay(1, ChronoUnit.MINUTES)
                    .skipOn(GatewayException.class)
                    .onStateChange(breakerTransitions::add)
                    .done()
                    .withTimeout().duration(PATIENT_TIMEOUT_SECONDS, ChronoUnit.SECONDS).done()
                    .build();
        }

        /**
         * A proxy route over the one-connection client, guarded by {@code guard}, served by a stage whose
         * request-body idle timeout is {@code idleTimeoutMillis}.
         */
        private Routed routed(long idleTimeoutMillis, Guard guard, boolean retryEnabled) {
            return routed(idleTimeoutMillis, upstreamClient, guard, retryEnabled);
        }

        /**
         * A proxy route over {@code client}, guarded by {@code guard}, served by a stage whose request-body
         * idle timeout is {@code idleTimeoutMillis}.
         */
        private Routed routed(long idleTimeoutMillis, HttpClient client, Guard guard, boolean retryEnabled) {
            return Routed.of("body-idle", new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstream.actualPort(), ""),
                    retryEnabled, client, guard, idleTimeoutMillis, failureMapper);
        }

        /** Runs one dispatch on a virtual thread, as the edge does, framed as its method directs. */
        private Future<HttpClientResponse> dispatch(Routed routed, HttpMethod method, String path,
                ReadStream<Buffer> inbound) {
            return virtualThreadExecutor.submit(() -> routed.dispatch(method, path, Map.of(), inbound,
                    DispatchStage.BodyFraming.of(method, DispatchStage.NO_DECLARED_LENGTH, _ -> {
                        // No body byte is ever sent on a bodyless dispatch here, so no late refusal arrives.
                    })));
        }

        /**
         * Awaits a dispatch and reads its whole answer.
         *
         * @return the status and body, or a description of the failure when the dispatch was not answered
         */
        private String answerOf(Future<HttpClientResponse> dispatched) throws Exception {
            HttpClientResponse response;
            try {
                response = Awaits.connect(dispatched, "the dispatch to be answered");
            } catch (ExecutionException noAnswer) {
                return "no answer: " + noAnswer.getCause();
            }
            io.vertx.core.Future<Buffer> body = response.body();
            response.resume();
            return response.statusCode() + " " + Awaits.connect(body, "the upstream answer body to arrive");
        }
    }

    @Nested
    @DisplayName("asset terminal-action serving")
    class AssetServing {

        @Test
        @DisplayName("serves a directory asset over GET behind the gateway response envelope")
        void servesDirectoryAssetOverGet(@TempDir Path root) throws Exception {
            Files.writeString(root.resolve("app.css"), "body{color:red}");
            DirectoryAssetSource source = new DirectoryAssetSource(root, AccessLevel.PUBLIC, null, null, Map.of());

            AssetSource.Served served = DispatchStage.serveAsset(source, HttpMethod.GET, "/app.css");

            assertEquals(200, served.status());
            assertEquals("text/css; charset=utf-8", served.headers().get("Content-Type"),
                    "the gateway sets the content type from its own extension map, not the source");
            assertEquals("nosniff", served.headers().get("X-Content-Type-Options"));
            assertEquals("body{color:red}", new String(served.body(), StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("serves a directory asset over HEAD with an empty body")
        void servesDirectoryAssetOverHead(@TempDir Path root) throws Exception {
            Files.writeString(root.resolve("app.css"), "body{color:red}");
            DirectoryAssetSource source = new DirectoryAssetSource(root, AccessLevel.PUBLIC, null, null, Map.of());

            AssetSource.Served served = DispatchStage.serveAsset(source, HttpMethod.HEAD, "/app.css");

            assertEquals(200, served.status());
            assertEquals(0, served.body().length, "a HEAD response carries the governed headers but no body");
        }

        @Test
        @DisplayName("rejects a non-read verb with 405 on the dispatch path (GET/HEAD-only)")
        void rejectsNonReadVerb(@TempDir Path root) throws Exception {
            Files.writeString(root.resolve("app.css"), "body{}");
            DirectoryAssetSource source = new DirectoryAssetSource(root, AccessLevel.PUBLIC, null, null, Map.of());

            AssetSource.Served served = DispatchStage.serveAsset(source, HttpMethod.POST, "/app.css");

            assertEquals(405, served.status(), "an asset action serves only GET and HEAD");
        }

        @Test
        @DisplayName("serves an upstream asset, forcing no-store on an authenticated route through the envelope")
        void servesUpstreamAssetGoverned() {
            ResolvedUpstream upstream = new ResolvedUpstream("https", "cdn.internal", 443, "");
            UpstreamAssetSource.UpstreamFetcher fetcher = _ -> new UpstreamAssetSource.UpstreamFetcher.Fetched(
                    200, Map.of("Content-Type", "text/plain", "Cache-Control", "public"),
                    "PNGDATA".getBytes(StandardCharsets.UTF_8), false);
            UpstreamAssetSource source = new UpstreamAssetSource(upstream, AccessLevel.AUTHENTICATED,
                    new PathConfinement(), fetcher, 1024L, Map.of());

            AssetSource.Served served = DispatchStage.serveAsset(source, HttpMethod.GET, "/logo.png");

            assertEquals(200, served.status());
            assertEquals("image/png", served.headers().get("Content-Type"),
                    "the gateway overrides the upstream content type from the extension map");
            assertEquals("no-store", served.headers().get("Cache-Control"),
                    "an authenticated asset is forced to no-store regardless of the upstream's Cache-Control");
        }
    }

    /**
     * The upstream response is paused before any of its body or its end can be processed, whatever
     * thread dispatched it and however late that thread catches up with the exchange. The relay is
     * deferred onto the client connection's event loop exactly as {@code GatewayEdgeRoute} defers it,
     * so a response that ended before the relay subscribed fails the relay with "Response already
     * ended" and the client is never answered.
     * <p>
     * The interleaving that loses this race is forced rather than hoped for: the upstream client is
     * wrapped so the dispatching virtual thread is held, right after it has chained the upstream send,
     * until the response head has been processed and the response has had the chance to end — the
     * state a dispatching thread that fell behind an immediately-answering upstream observes.
     */
    @Nested
    @DisplayName("upstream response pause against a relay deferred onto the event loop")
    class DeferredRelay {

        private static final String TINY_BODY = "tiny";

        private final AtomicReference<Throwable> relayFailure = new AtomicReference<>();
        private final ExchangeStall stall = new ExchangeStall();

        private Vertx vertx;
        private HttpServer upstream;
        private HttpClient upstreamClient;
        private HttpServer front;
        private HttpClient testClient;

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            upstream = Awaits.connect(vertx.createHttpServer()
                            .requestHandler(request -> request.response().end(TINY_BODY))
                            .listen(0, LoopbackHost.ADDRESS),
                    "the immediately-answering upstream to start listening");
            upstreamClient = vertx.createHttpClient();
            Routed routed = Routed.of("deferred-relay",
                    new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstream.actualPort(), ""), false,
                    interceptingClient(upstreamClient, stall::hold),
                    Guard.create()
                            .withRetry().maxRetries(2).delay(0, ChronoUnit.MILLIS)
                            .whenException(DispatchStage::allowsRetryAfter).done()
                            .build(),
                    DispatchStage.NO_BODY_IDLE_TIMEOUT, new UpstreamFailureMapper(new GatewayEventCounter()));
            ResponseStage responseStage = new ResponseStage(Set.of());
            front = Awaits.connect(vertx.createHttpServer().requestHandler(request -> {
                // As GatewayEdgeRoute does: pause the inbound request on its event loop, capture that
                // loop's context, and run the dispatch on a virtual thread.
                request.pause();
                Context clientContext = Vertx.currentContext();
                Thread.ofVirtual().start(
                        () -> dispatchThenDeferRelay(routed, responseStage, request, clientContext));
            }).listen(0, LoopbackHost.ADDRESS), "the dispatching front server to start listening");
            testClient = vertx.createHttpClient();
        }

        @AfterEach
        void tearDown() throws Exception {
            Awaits.teardown(testClient.close(), "the test client to close");
            Awaits.teardown(front.close(), "the dispatching front server to close");
            Awaits.teardown(upstreamClient.close(), "the upstream client to close");
            Awaits.teardown(upstream.close(), "the upstream to close");
            Awaits.teardown(vertx.close(), "Vert.x to close");
        }

        @Test
        @DisplayName("relays a tiny body that completed before the dispatching virtual thread caught up")
        void relaysTinyBodyThatOutranTheDispatchingThread() throws Exception {
            String relayed = Awaits.connect(testClient
                            .request(io.vertx.core.http.HttpMethod.GET, front.actualPort(), LoopbackHost.ADDRESS, "/")
                            .compose(HttpClientRequest::send)
                            .compose(response -> response.body().map(body -> response.statusCode() + " " + body))
                            .recover(noAnswer -> io.vertx.core.Future.succeededFuture("no answer: " + noAnswer.getMessage())),
                    "the relayed response");

            assertAll("the deferred relay streams the whole upstream answer",
                    () -> assertTrue(stall.heldPastHead(),
                            "the dispatching thread must have been held until the upstream head was processed,"
                                    + " otherwise this run never reached the interleaving under test"),
                    () -> assertNull(relayFailure.get(),
                            () -> "the deferred relay must find the upstream response still paused, but it failed: "
                                    + relayFailure.get()),
                    () -> assertEquals("200 " + TINY_BODY, relayed,
                            "the client receives the upstream status and body, not an abandoned connection"));
        }

        private void dispatchThenDeferRelay(Routed routed, ResponseStage responseStage, HttpServerRequest request,
                Context clientContext) {
            HttpClientResponse response;
            try {
                response = routed.dispatch(HttpMethod.GET, "/", Map.of(), request,
                        DispatchStage.BodyFraming.of(HttpMethod.GET, DispatchStage.NO_DECLARED_LENGTH, _ -> {
                            // A body-free GET: no late refusal can arrive in this test.
                        }));
            } catch (GatewayException dispatchFailure) {
                abandon(request, dispatchFailure);
                return;
            }
            clientContext.runOnContext(_ -> {
                try {
                    responseStage.relay(response, request.response(), false, null, Map.of(), Map.of(),
                            Supplier::get)
                            .onFailure(failure -> abandon(request, failure));
                } catch (IllegalStateException relayStartFailure) {
                    abandon(request, relayStartFailure);
                }
            });
        }

        /** Records why the relay could not answer and closes the client connection so the test fails fast. */
        private void abandon(HttpServerRequest request, Throwable failure) {
            relayFailure.set(failure);
            request.connection().close();
        }
    }

    /**
     * Runs on the dispatching thread inside {@code compose(...)} of an upstream request future —
     * after the send has been chained, before anything else is — with the composed exchange.
     */
    @FunctionalInterface
    private interface ComposeHook {

        void afterCompose(io.vertx.core.Future<?> composed) throws Exception;
    }

    /**
     * Wraps {@code delegate} so every upstream request future it hands out runs {@code hook} on the
     * calling thread inside {@code compose(...)}, with the composed exchange.
     */
    private static HttpClient interceptingClient(HttpClient delegate, ComposeHook hook) {
        return (HttpClient) Proxy.newProxyInstance(HttpClient.class.getClassLoader(),
                new Class<?>[]{HttpClient.class}, (_, method, args) -> {
                    Object result = invokeOn(delegate, method, args);
                    if ("request".equals(method.getName()) && result instanceof io.vertx.core.Future<?> requestFuture) {
                        return interceptingCompose(requestFuture, hook);
                    }
                    return result;
                });
    }

    private static io.vertx.core.Future<?> interceptingCompose(io.vertx.core.Future<?> delegate, ComposeHook hook) {
        return (io.vertx.core.Future<?>) Proxy.newProxyInstance(io.vertx.core.Future.class.getClassLoader(),
                new Class<?>[]{io.vertx.core.Future.class}, (_, method, args) -> {
                    Object result = invokeOn(delegate, method, args);
                    if ("compose".equals(method.getName()) && result instanceof io.vertx.core.Future<?> composed) {
                        hook.afterCompose(composed);
                    }
                    return result;
                });
    }

    private static @Nullable Object invokeOn(Object target, Method method, Object @Nullable [] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    /**
     * Holds the dispatching thread until the composed upstream exchange has delivered its response
     * head, then for up to {@link #END_GRACE_MILLIS} more while that response ends. A response paused
     * at its head cannot end while held, so for a correctly paused response the grace simply elapses.
     */
    private static final class ExchangeStall {

        private static final long END_GRACE_MILLIS = 1000L;

        private final AtomicBoolean heldPastHead = new AtomicBoolean();

        void hold(io.vertx.core.Future<?> composed) throws Exception {
            CompletableFuture<@Nullable Object> head = new CompletableFuture<>();
            CompletableFuture<@Nullable Object> ended = new CompletableFuture<>();
            composed.onComplete(result -> {
                if (result.result() instanceof HttpClientResponse response) {
                    response.end().onComplete(_ -> ended.complete(null));
                }
                head.complete(result.result());
            });
            heldPastHead.set(Awaits.connect(head, "the upstream response head to be processed") != null);
            ended.completeOnTimeout(null, END_GRACE_MILLIS, TimeUnit.MILLISECONDS).join();
        }

        boolean heldPastHead() {
            return heldPastHead.get();
        }
    }

    /**
     * A minimal {@link ReadStream} fake: captures the handler the decorator installs and lets a test
     * push buffers synchronously through it.
     */
    private static final class TestReadStream implements ReadStream<Buffer> {

        // Volatile: the live-upstream tests register the handlers on a Vert.x thread and drive the
        // stream from the test thread.
        private volatile @Nullable Handler<Buffer> handler;
        private volatile @Nullable Handler<Throwable> exceptionHandler;
        private volatile @Nullable Handler<Void> endHandler;
        private volatile boolean resumed;
        /** Whether the stream is paused now: set by {@link #pause()}, cleared by a resume or a fetch. */
        private volatile boolean paused;
        private volatile @Nullable Buffer onResume;

        /** @return {@code true} while the last call that changed the flow was a pause */
        boolean paused() {
            return paused;
        }

        /** Emits {@code buffer} the moment a consumer resumes the stream, before {@link #resume()} returns. */
        void emitOnResume(Buffer buffer) {
            onResume = buffer;
        }

        void emit(Buffer buffer) {
            Handler<Buffer> current = handler;
            if (current != null) {
                current.handle(buffer);
            }
        }

        /** Fails the stream, as an inbound connection whose body did not arrive whole does. */
        void fail(Throwable cause) {
            Handler<Throwable> current = exceptionHandler;
            if (current != null) {
                current.handle(cause);
            }
        }

        /** Ends the stream: the body arrived whole. */
        void end() {
            Handler<Void> current = endHandler;
            if (current != null) {
                current.handle(null);
            }
        }

        /** @return {@code true} once a consumer has both registered its data handler and asked for data */
        boolean subscribed() {
            return resumed && handler != null;
        }

        @Override
        public ReadStream<Buffer> handler(@Nullable Handler<Buffer> handler) {
            this.handler = handler;
            return this;
        }

        @Override
        public ReadStream<Buffer> exceptionHandler(@Nullable Handler<Throwable> exceptionHandler) {
            this.exceptionHandler = exceptionHandler;
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            paused = true;
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            resumed = true;
            paused = false;
            Buffer pending = onResume;
            if (pending != null) {
                onResume = null;
                emit(pending);
            }
            return this;
        }

        @Override
        public ReadStream<Buffer> fetch(long amount) {
            paused = false;
            return this;
        }

        @Override
        public ReadStream<Buffer> endHandler(@Nullable Handler<Void> endHandler) {
            this.endHandler = endHandler;
            return this;
        }
    }
}
