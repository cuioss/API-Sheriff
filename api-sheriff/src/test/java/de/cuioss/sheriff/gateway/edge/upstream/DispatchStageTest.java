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
package de.cuioss.sheriff.gateway.edge.upstream;

import static org.junit.jupiter.api.Assertions.*;

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
import java.util.OptionalLong;
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
import de.cuioss.sheriff.gateway.config.model.CookiesConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedAsset;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.sheriff.gateway.edge.assembly.ResilienceShape;
import de.cuioss.sheriff.gateway.edge.assembly.RouteAssembler;
import de.cuioss.sheriff.gateway.edge.assembly.SecurityPosture;
import de.cuioss.sheriff.gateway.edge.assembly.UpstreamClients;
import de.cuioss.sheriff.gateway.edge.assembly.UpstreamTarget;
import de.cuioss.sheriff.gateway.edge.relay.ResponseStage;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayEventCounter;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.http.GatewayCookies;
import de.cuioss.sheriff.gateway.routing.ProtocolProcessorRegistry;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.sheriff.gateway.routing.RouteTable;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.ConfigFixtures;
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
import io.vertx.core.http.HttpVersion;
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

    /** Length of a gRPC message prefix: one compressed-flag byte and a 4-byte big-endian payload length. */
    private static final int GRPC_PREFIX_LENGTH = 5;

    /** @return {@code payload} framed as one uncompressed gRPC length-prefixed message */
    private static Buffer grpcMessage(String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        return Buffer.buffer().appendByte((byte) 0).appendInt(bytes.length).appendBytes(bytes);
    }

    /**
     * A proxy route together with the stage that serves it. The route's shared client and guard are
     * not route components: the stage resolves them from its {@link UpstreamClients}
     * lookup, which this fixture fills for the route exactly as the boot assembly would.
     */
    private record Routed(Route route, DispatchStage stage) {

        static Routed of(String id, ResolvedUpstream upstream, boolean retryEnabled, HttpClient client, Guard guard,
                long bodyIdleTimeoutMillis, UpstreamFailureMapper failureMapper) {
            return of(id, upstream, retryEnabled, client, guard, bodyIdleTimeoutMillis, DispatchStage.NO_BODY_DEADLINE,
                    failureMapper);
        }

        /** A route served by the public constructor: an HTTP dispatch, which bounds no message wait. */
        static Routed of(String id, ResolvedUpstream upstream, boolean retryEnabled, HttpClient client, Guard guard,
                long bodyIdleTimeoutMillis, long bodyDeadlineMillis, UpstreamFailureMapper failureMapper) {
            Route route = route(id, upstream, retryEnabled);
            return new Routed(route, new DispatchStage(clientsFor(route, client, guard), BODY_CAP,
                    bodyIdleTimeoutMillis, bodyDeadlineMillis, failureMapper));
        }

        /**
         * A route served by a stage that bounds the wait for each complete request message by
         * {@code messageTimeoutMillis} and arms no deadline.
         */
        static Routed withMessageBound(String id, ResolvedUpstream upstream, HttpClient client, Guard guard,
                long messageTimeoutMillis, UpstreamFailureMapper failureMapper) {
            Route route = route(id, upstream, false);
            return new Routed(route, new DispatchStage(clientsFor(route, client, guard), BODY_CAP,
                    DispatchStage.NO_BODY_IDLE_TIMEOUT, DispatchStage.NO_BODY_DEADLINE, messageTimeoutMillis,
                    DispatchStage.NO_PRE_RESPONSE_CAP, DispatchStage.NO_HAND_OVER_GRACE, DispatchStage.NO_UPSTREAM_STALL,
                    failureMapper));
        }

        private static Route route(String id, ResolvedUpstream upstream, boolean retryEnabled) {
            return Route.builder()
                    .id(id)
                    .match(MatchConfig.builder().pathPrefix("/" + id).build())
                    .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                    .upstream(upstream)
                    .retryEnabled(retryEnabled)
                    .build();
        }

        private static UpstreamClients clientsFor(Route route, HttpClient client, Guard guard) {
            UpstreamTarget target = UpstreamTarget.of(route);
            return new UpstreamClients(Map.of(target, client),
                    Map.of(new ResilienceShape(target, route.retryEnabled()), guard));
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
    @DisplayName("constructor refusals of the request-body bounds")
    class BodyBoundRefusals {

        private static final long IDLE_TIMEOUT_MILLIS = 300L;
        private static final long DEADLINE_MILLIS = 6 * IDLE_TIMEOUT_MILLIS;
        /** A hand-over grace a pre-response deadline accepts: positive and shorter than the deadline. */
        private static final long GRACE_MILLIS = IDLE_TIMEOUT_MILLIS / 3;
        /** An upstream stall threshold a pre-response deadline accepts: positive and shorter than the deadline. */
        private static final long STALL_MILLIS = IDLE_TIMEOUT_MILLIS;

        private final UpstreamClients noClients = new UpstreamClients(Map.of(), Map.of());
        private final UpstreamFailureMapper failureMapper = new UpstreamFailureMapper(new GatewayEventCounter());

        @ParameterizedTest(name = "{0} ms")
        @ValueSource(longs = {-1L, Long.MIN_VALUE})
        @DisplayName("refuses a negative request-body deadline")
        void refusesANegativeDeadline(long deadlineMillis) {
            assertThrows(IllegalArgumentException.class, () -> new DispatchStage(noClients, BODY_CAP,
                    IDLE_TIMEOUT_MILLIS, deadlineMillis, failureMapper));
        }

        @Test
        @DisplayName("refuses a request-body deadline without a request-body idle timeout")
        void refusesADeadlineWithoutAnIdleTimeout() {
            assertThrows(IllegalArgumentException.class, () -> new DispatchStage(noClients, BODY_CAP,
                    DispatchStage.NO_BODY_IDLE_TIMEOUT, DEADLINE_MILLIS, failureMapper));
        }

        @Test
        @DisplayName("control: builds with a deadline and an idle timeout, a deadline and a message bound, and with neither")
        void buildsWithBothBoundsOrNeither() {
            assertAll("the accepted combinations",
                    () -> assertDoesNotThrow(() -> new DispatchStage(noClients, BODY_CAP, IDLE_TIMEOUT_MILLIS,
                            DEADLINE_MILLIS, failureMapper)),
                    () -> assertDoesNotThrow(() -> new DispatchStage(noClients, BODY_CAP,
                                    DispatchStage.NO_BODY_IDLE_TIMEOUT, DEADLINE_MILLIS, IDLE_TIMEOUT_MILLIS,
                                    DEADLINE_MILLIS, GRACE_MILLIS, STALL_MILLIS, failureMapper),
                            "a message bound carries the deadline as the pre-response deadline, held to a cap"),
                    () -> assertDoesNotThrow(() -> new DispatchStage(noClients, BODY_CAP,
                            DispatchStage.NO_BODY_IDLE_TIMEOUT, DispatchStage.NO_BODY_DEADLINE, failureMapper)));
        }

        /**
         * A pre-response cap shorter than the deadline would cut the deadline down for every attempt, however
         * promptly it handed its body over; one at {@link DispatchStage#NO_PRE_RESPONSE_CAP} would leave the
         * deadline uncapped, free to run past the guard's timeout.
         */
        @ParameterizedTest(name = "a cap {0} ms short of the deadline")
        @ValueSource(longs = {1L, DEADLINE_MILLIS})
        @DisplayName("refuses a pre-response deadline held to a cap shorter than itself, or to none")
        void refusesAPreResponseCapShorterThanTheDeadline(long shortOfTheDeadlineMillis) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new DispatchStage(noClients, BODY_CAP, DispatchStage.NO_BODY_IDLE_TIMEOUT, DEADLINE_MILLIS,
                            IDLE_TIMEOUT_MILLIS, DEADLINE_MILLIS - shortOfTheDeadlineMillis, GRACE_MILLIS, STALL_MILLIS,
                            failureMapper));

            assertTrue(refused.getMessage().contains("pre-response deadline needs a cap"), refused::getMessage);
        }

        /**
         * A hand-over grace that is not positive would dispatch a hand-over just short of the cap with a deadline
         * too short for an end the client already sent to arrive; one no shorter than the deadline would refuse
         * even a prompt hand-over.
         */
        @ParameterizedTest(name = "a grace of {0} ms")
        @ValueSource(longs = {DispatchStage.NO_HAND_OVER_GRACE, -1L, DEADLINE_MILLIS})
        @DisplayName("refuses a pre-response deadline with a hand-over grace that is not positive or not shorter than itself")
        void refusesAPreResponseGraceNotPositiveOrNotShorterThanTheDeadline(long graceMillis) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new DispatchStage(noClients, BODY_CAP, DispatchStage.NO_BODY_IDLE_TIMEOUT, DEADLINE_MILLIS,
                            IDLE_TIMEOUT_MILLIS, DEADLINE_MILLIS, graceMillis, STALL_MILLIS, failureMapper));

            assertTrue(refused.getMessage().contains("positive hand-over grace"), refused::getMessage);
        }

        /**
         * An upstream stall threshold that is not positive would charge every flow-control pause at the deadline to
         * the upstream; one no shorter than the deadline could never be reached by a stall that starts within it.
         */
        @ParameterizedTest(name = "a stall threshold of {0} ms")
        @ValueSource(longs = {DispatchStage.NO_UPSTREAM_STALL, -1L, DEADLINE_MILLIS})
        @DisplayName("refuses a pre-response deadline with an upstream stall threshold that is not positive or not shorter than itself")
        void refusesAPreResponseStallThresholdNotPositiveOrNotShorterThanTheDeadline(long stallMillis) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new DispatchStage(noClients, BODY_CAP, DispatchStage.NO_BODY_IDLE_TIMEOUT, DEADLINE_MILLIS,
                            IDLE_TIMEOUT_MILLIS, DEADLINE_MILLIS, GRACE_MILLIS, stallMillis, failureMapper));

            assertTrue(refused.getMessage().contains("positive upstream stall threshold"), refused::getMessage);
        }

        /** A stall threshold with no pre-response deadline to attribute would be silently ignored, so it is refused. */
        @ParameterizedTest(name = "idle timeout {0} ms, deadline {1} ms")
        @CsvSource({"300, 1800", "0, 0"})
        @DisplayName("refuses an upstream stall threshold on a stage that arms no pre-response deadline")
        void refusesAStallThresholdWithoutAPreResponseDeadline(long idleTimeoutMillis, long deadlineMillis) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new DispatchStage(noClients, BODY_CAP, idleTimeoutMillis, deadlineMillis,
                            IDLE_TIMEOUT_MILLIS, DispatchStage.NO_PRE_RESPONSE_CAP, DispatchStage.NO_HAND_OVER_GRACE,
                            STALL_MILLIS, failureMapper));

            assertTrue(refused.getMessage().contains("stall threshold needs a pre-response deadline"),
                    refused::getMessage);
        }

        /** A grace with no pre-response deadline to hold would be silently ignored, so it is refused. */
        @ParameterizedTest(name = "idle timeout {0} ms, deadline {1} ms")
        @CsvSource({"300, 1800", "0, 0"})
        @DisplayName("refuses a hand-over grace on a stage that arms no pre-response deadline")
        void refusesAGraceWithoutAPreResponseDeadline(long idleTimeoutMillis, long deadlineMillis) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new DispatchStage(noClients, BODY_CAP, idleTimeoutMillis, deadlineMillis,
                            IDLE_TIMEOUT_MILLIS, DispatchStage.NO_PRE_RESPONSE_CAP, GRACE_MILLIS,
                            DispatchStage.NO_UPSTREAM_STALL, failureMapper));

            assertTrue(refused.getMessage().contains("hand-over grace needs a pre-response deadline"),
                    refused::getMessage);
        }

        /** A cap with no pre-response deadline to hold would be silently ignored, so it is refused. */
        @ParameterizedTest(name = "idle timeout {0} ms, deadline {1} ms")
        @CsvSource({"300, 1800", "0, 0"})
        @DisplayName("refuses a pre-response cap on a stage that arms no pre-response deadline")
        void refusesACapWithoutAPreResponseDeadline(long idleTimeoutMillis, long deadlineMillis) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new DispatchStage(noClients, BODY_CAP, idleTimeoutMillis, deadlineMillis,
                            IDLE_TIMEOUT_MILLIS, DEADLINE_MILLIS, DispatchStage.NO_HAND_OVER_GRACE,
                            DispatchStage.NO_UPSTREAM_STALL, failureMapper));

            assertTrue(refused.getMessage().contains("needs a pre-response deadline"), refused::getMessage);
        }

        /**
         * The byte-capped body schedules its request-body deadline through the clock it is armed with, so a
         * deadline handed to a body that arms neither the idle clock nor the message clock could never fire;
         * the body refuses it when it is built rather than carrying a deadline that is silently never enforced.
         */
        @Test
        @DisplayName("a byte-capped body refuses a request-body deadline when neither its idle clock nor its message clock is armed")
        void byteCappedBodyRefusesADeadlineWithoutAnArmedClock() {
            TestReadStream source = new TestReadStream();
            OptionalLong deadline = OptionalLong.of(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MILLIS));

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new DispatchStage.ByteCappedBodyStream(source, BODY_CAP, DispatchStage.NO_DECLARED_LENGTH,
                            () -> fail("a refused body never aborts anything"), _ -> {
                                // Nothing is forwarded by a body that is never built.
                            }, DispatchStage.IdleClock.UNARMED, deadline, DispatchStage.IdleClock.UNARMED,
                            DispatchStage.NO_UPSTREAM_STALL));

            assertAll("a deadline with no armed clock to schedule it",
                    () -> assertTrue(refused.getMessage().contains("needs an armed idle or message clock"),
                            refused::getMessage),
                    () -> assertFalse(source.subscribed(), "the refused body never subscribes the inbound stream"));
        }

        /**
         * The byte-capped body decides by its upstream stall threshold whose failure a passed pre-response deadline
         * is, so a pre-response deadline — armed with the message clock, no idle clock — handed to a body whose
         * threshold is not positive would charge every pause at the deadline, however brief, to the upstream. The
         * body refuses it when it is built, whoever built it: this reaches the body's own check, not the stage's.
         */
        @ParameterizedTest(name = "a stall threshold of {0} ms")
        @ValueSource(longs = {DispatchStage.NO_UPSTREAM_STALL, -1L})
        @DisplayName("a byte-capped body refuses a pre-response deadline without a positive upstream stall threshold")
        void byteCappedBodyRefusesAPreResponseDeadlineWithoutAPositiveStallThreshold(long stallMillis) {
            TestReadStream source = new TestReadStream();
            ManualTime time = new ManualTime();
            OptionalLong deadline = OptionalLong.of(time.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MILLIS));
            DispatchStage.IdleClock messageClock = new DispatchStage.IdleClock(IDLE_TIMEOUT_MILLIS, time, time::nanoTime);

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new DispatchStage.ByteCappedBodyStream(source, BODY_CAP, DispatchStage.NO_DECLARED_LENGTH,
                            () -> fail("a refused body never aborts anything"), _ -> {
                                // Nothing is forwarded by a body that is never built.
                            }, DispatchStage.IdleClock.UNARMED, deadline, messageClock, stallMillis));

            assertAll("a pre-response deadline with no positive stall threshold to attribute it by",
                    () -> assertTrue(refused.getMessage().contains("positive upstream stall threshold"),
                            refused::getMessage),
                    () -> assertFalse(source.subscribed(), "the refused body never subscribes the inbound stream"));
        }

        /**
         * Matched control: the refusal belongs to the pre-response deadline alone. The same body builds once its
         * threshold is positive, and a whole-body deadline — armed with the idle clock, never charged to the
         * upstream — builds without a threshold.
         */
        @Test
        @DisplayName("control: a byte-capped body builds a pre-response deadline with a positive stall threshold, and a whole-body deadline without one")
        void byteCappedBodyBuildsEachDeadlineWithTheThresholdItNeeds() {
            ManualTime time = new ManualTime();
            OptionalLong deadline = OptionalLong.of(time.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MILLIS));
            DispatchStage.IdleClock clock = new DispatchStage.IdleClock(IDLE_TIMEOUT_MILLIS, time, time::nanoTime);

            assertAll("the accepted deadlines",
                    () -> assertDoesNotThrow(() -> new DispatchStage.ByteCappedBodyStream(new TestReadStream(), BODY_CAP,
                                    DispatchStage.NO_DECLARED_LENGTH, () -> fail("a body that is only built aborts nothing"), _ -> {
                                        // Nothing is forwarded by a body that is only built.
                                    }, DispatchStage.IdleClock.UNARMED, deadline, clock, STALL_MILLIS),
                            "a pre-response deadline with a positive stall threshold"),
                    () -> assertDoesNotThrow(() -> new DispatchStage.ByteCappedBodyStream(new TestReadStream(), BODY_CAP,
                                    DispatchStage.NO_DECLARED_LENGTH, () -> fail("a body that is only built aborts nothing"), _ -> {
                                        // Nothing is forwarded by a body that is only built.
                                    }, clock, deadline, DispatchStage.IdleClock.UNARMED, DispatchStage.NO_UPSTREAM_STALL),
                            "a whole-body deadline needs no stall threshold"));
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

    /**
     * The request-body deadline of {@link DispatchStage.ByteCappedBodyStream}, driven by hand on a
     * {@link ManualTime}. Once it has passed, the deadline stays in force: a body the consumer had paused
     * when it passed, and a body whose attempt obtained its connection only after it passed, are ended the
     * moment they are read again, on the same path as an expiry on time. An attempt the dispatch no longer
     * waits for stops its body's idle clock and deadline, and a stopped clock reports nothing.
     */
    @Nested
    @DisplayName("request-body deadline of the byte-capped body")
    class BodyDeadline {

        private static final long TIMEOUT_MILLIS = 1_000L;
        private static final long DEADLINE_MILLIS = 4 * TIMEOUT_MILLIS;

        @ParameterizedTest(name = "read again by {0}")
        @ValueSource(strings = {"resume", "fetch"})
        @DisplayName("a deadline that passed while the consumer had paused the body ends it as soon as the body is read again")
        void deadlinePassedWhilePausedEndsTheBodyWhenItIsReadAgain(String restart) {
            IdleProbe probe = deadlineProbe(DEADLINE_MILLIS);
            probe.capped.startIdleClock();
            probe.capped.pause();

            probe.time.advanceMillis(2 * DEADLINE_MILLIS);
            boolean quietWhilePaused = probe.failure.get() == null && probe.aborts.get() == 0;
            readAgain(probe, restart);

            GatewayException refusal = probe.capped.clientAbort();
            assertAll("a body paused by its consumer when its deadline passed",
                    () -> assertTrue(quietWhilePaused, "nothing ends the body while the upstream is not taking data"),
                    () -> assertInstanceOf(DispatchStage.RequestBodyDeadlineExceeded.class, refusal,
                            "reading the body again ends it at its deadline"),
                    () -> assertSame(refusal, probe.failure.get(), "the refusal is propagated to the consumer"),
                    () -> assertEquals(1, probe.aborts.get(), "the upstream request is reset exactly once"),
                    () -> assertTrue(probe.source.paused(),
                            "the inbound stream stays paused: the read that ended the body never reaches it"),
                    () -> assertEquals(0, probe.time.pendingCount(), "nothing is pending once the body was ended"));
        }

        /**
         * A read checks the passed deadline before the idle clock resumes and again once it has. A deadline
         * timer that fires between the two, while the stream is still marked paused, only records that the
         * deadline passed; the second check then ends the body. The timer is placed in that window through
         * the stream's read seam, so the interleaving is forced rather than hoped for.
         */
        @ParameterizedTest(name = "read again by {0}")
        @ValueSource(strings = {"resume", "fetch"})
        @DisplayName("a deadline timer firing between a read's first deadline check and the clock's resume still ends the body")
        void deadlineTimerInsideTheReadWindowStillEndsTheBody(String restart) {
            IdleProbe probe = deadlineProbe(DEADLINE_MILLIS);
            probe.capped.startIdleClock();
            probe.capped.pause();
            probe.time.advanceMillis(DEADLINE_MILLIS - 1);
            boolean quietBeforeTheDeadline = probe.failure.get() == null && probe.aborts.get() == 0;
            Runnable deadlineTimerFires = () -> probe.time.advanceMillis(1);

            if ("resume".equals(restart)) {
                probe.capped.resume(deadlineTimerFires);
            } else {
                probe.capped.fetch(1, deadlineTimerFires);
            }

            GatewayException refusal = probe.capped.clientAbort();
            assertAll("a read whose window a deadline timer fired in",
                    () -> assertTrue(quietBeforeTheDeadline, "nothing ends the body before its deadline"),
                    () -> assertInstanceOf(DispatchStage.RequestBodyDeadlineExceeded.class, refusal,
                            "the read ends the body at its deadline"),
                    () -> assertSame(refusal, probe.failure.get(), "the refusal is propagated to the consumer"),
                    () -> assertEquals(1, probe.aborts.get(), "the upstream request is reset exactly once"),
                    () -> assertTrue(probe.source.paused(),
                            "the inbound stream stays paused: the read that ended the body never reaches it"),
                    () -> assertEquals(0, probe.time.pendingCount(), "nothing is pending once the body was ended"));
        }

        /**
         * A timer never fires early, but it may fire late. The whole-body deadline has no attribution to decide,
         * yet a read that ends a pause still ends the body itself once the deadline's instant has passed — also
         * when the deadline's timer has not run yet, so the read does not hand a body past its deadline back to
         * the inbound stream first.
         */
        @ParameterizedTest(name = "read again by {0}")
        @ValueSource(strings = {"resume", "fetch"})
        @DisplayName("a read after the deadline's instant ends the paused body although the deadline's late timer has not run")
        void readAfterTheDeadlineInstantEndsThePausedBodyBeforeItsLateTimerRan(String restart) {
            IdleProbe probe = deadlineProbe(DEADLINE_MILLIS);
            probe.capped.startIdleClock();
            probe.capped.pause();
            probe.time.advanceMillisHoldingTimers(DEADLINE_MILLIS);
            boolean quietBeforeTheRead = probe.failure.get() == null && probe.aborts.get() == 0;
            int pendingBeforeTheRead = probe.time.pendingCount();

            readAgain(probe, restart);

            GatewayException refusal = probe.capped.clientAbort();
            assertAll("a paused body read again after its deadline's instant, the deadline's timer still pending",
                    () -> assertTrue(quietBeforeTheRead, "nothing ends the body before it is read again"),
                    () -> assertEquals(2, pendingBeforeTheRead, "the idle timer and the deadline's timer have not run"),
                    () -> assertInstanceOf(DispatchStage.RequestBodyDeadlineExceeded.class, refusal,
                            "the read ends the body at its deadline without waiting for the late timer"),
                    () -> assertSame(refusal, probe.failure.get(), "the refusal is propagated to the consumer"),
                    () -> assertEquals(1, probe.aborts.get(), "the upstream request is reset exactly once"),
                    () -> assertTrue(probe.source.paused(),
                            "the inbound stream stays paused: the read that ended the body never reaches it"),
                    () -> assertEquals(0, probe.time.pendingCount(), "the claim cancelled both late timers"));
        }

        @Test
        @DisplayName("after the hand-over, a body read again past its deadline reaches the late-violation callback before the reset")
        void deadlinePassedWhilePausedAfterTheHandOverGoesToTheLateCallback() {
            IdleProbe probe = deadlineProbe(DEADLINE_MILLIS);
            probe.capped.startIdleClock();
            // The attempt's send completed with the response head: the idle clock stops, the deadline runs on.
            probe.capped.stopIdleClock();
            GatewayException atTheHandOver = probe.handOver();
            probe.capped.pause();

            probe.time.advanceMillis(2 * DEADLINE_MILLIS);
            probe.capped.resume();

            GatewayException refusal = probe.capped.clientAbort();
            assertAll("a relayed answer whose body is read again past its deadline",
                    () -> assertNull(atTheHandOver, "nothing was refused when the response was handed over"),
                    () -> assertInstanceOf(DispatchStage.RequestBodyDeadlineExceeded.class, refusal),
                    () -> assertEquals(Collections.singletonList(refusal), probe.lateViolations,
                            "the refusal reaches the late-violation callback once"),
                    () -> assertEquals(List.of(IdleProbe.LATE, IdleProbe.ABORT), probe.sequence,
                            "the edge learns of the refusal before the upstream request is reset"));
        }

        /**
         * A timer never fires early, but it may fire late, and the body's end can arrive in between: after the
         * deadline's instant, before the deadline's timer has run. The body was still arriving when its deadline
         * passed, so that end comes too late and the body is ended at its deadline — after the hand-over through
         * the late-violation callback, before the reset, exactly as the timer would have ended it on time. Left to
         * the end itself, the late timer would be cancelled and the body passed on as one that had ended in time.
         */
        @Test
        @DisplayName("after the hand-over, an end arriving after the deadline's instant, before the deadline's late timer has run, reaches the late-violation callback before the reset")
        void inboundEndAfterTheDeadlineInstantAfterTheHandOverGoesToTheLateCallback() {
            IdleProbe probe = deadlineProbe(DEADLINE_MILLIS);
            probe.capped.startIdleClock();
            // The attempt's send completed with the response head: the idle clock stops, the deadline runs on.
            probe.capped.stopIdleClock();
            GatewayException atTheHandOver = probe.handOver();
            probe.time.advanceMillisHoldingTimers(DEADLINE_MILLIS);
            int pendingBeforeTheEnd = probe.time.pendingCount();

            probe.source.end();

            GatewayException refusal = probe.capped.clientAbort();
            assertAll("a relayed answer whose body ends after its deadline's instant, the deadline's timer still pending",
                    () -> assertNull(atTheHandOver, "nothing was refused when the response was handed over"),
                    () -> assertEquals(1, pendingBeforeTheEnd, "the deadline's timer has not run"),
                    () -> assertInstanceOf(DispatchStage.RequestBodyDeadlineExceeded.class, refusal,
                            "the body was still arriving at its deadline's instant: the end comes too late"),
                    () -> assertEquals(Collections.singletonList(refusal), probe.lateViolations,
                            "the refusal reaches the late-violation callback once"),
                    () -> assertEquals(List.of(IdleProbe.LATE, IdleProbe.ABORT), probe.sequence,
                            "the edge learns of the refusal before the upstream request is reset"),
                    () -> assertFalse(probe.ended.get(), "the late end is not passed on"),
                    () -> assertEquals(0, probe.source.pauseCount(),
                            "the ended inbound stream is not paused: an ended HTTP/2 request refuses a pause"),
                    () -> assertEquals(0, probe.time.pendingCount(), "the claim cancelled the late timer"));
        }

        /**
         * The end of the body never drops a passed deadline. A stream its consumer has paused delivers no end
         * before it is read again, and that read ends the body first; this stream delivers one all the same, and
         * the deadline recorded as passed during the pause still ends the body.
         */
        @Test
        @DisplayName("an end delivered while a deadline recorded as passed is still in force does not drop it: the body is ended at its deadline")
        void inboundEndDoesNotDropADeadlineRecordedAsPassed() {
            IdleProbe probe = deadlineProbe(DEADLINE_MILLIS);
            probe.capped.startIdleClock();
            probe.capped.pause();
            probe.time.advanceMillis(2 * DEADLINE_MILLIS);
            boolean quietWhilePaused = probe.failure.get() == null && probe.aborts.get() == 0;

            probe.source.end();

            GatewayException refusal = probe.capped.clientAbort();
            assertAll("a body whose end is delivered after its deadline was recorded as passed",
                    () -> assertTrue(quietWhilePaused, "nothing ends the body while the upstream is not taking data"),
                    () -> assertInstanceOf(DispatchStage.RequestBodyDeadlineExceeded.class, refusal,
                            "the passed deadline ends the body: its end comes too late"),
                    () -> assertSame(refusal, probe.failure.get(), "the refusal is propagated to the consumer"),
                    () -> assertFalse(probe.ended.get(), "the late end is not passed on"),
                    () -> assertEquals(1, probe.aborts.get(), "the upstream request is reset exactly once"),
                    () -> assertEquals(1, probe.source.pauseCount(),
                            "the consumer's pause stays the only one: the ended inbound stream is not paused again"),
                    () -> assertEquals(0, probe.time.pendingCount(), "nothing is pending once the body was ended"));
        }

        @Test
        @DisplayName("a deadline that passed before the attempt held its connection ends the body as soon as it is first read")
        void deadlinePassedBeforeTheClockStartedEndsTheBodyWhenItIsFirstRead() {
            IdleProbe probe = deadlineProbe(0L);
            // The attempt obtains its connection only after its deadline passed.
            probe.time.advanceMillis(1);
            probe.capped.startIdleClock();
            boolean quietBeforeTheFirstRead = probe.failure.get() == null && probe.aborts.get() == 0;

            probe.capped.resume();

            GatewayException refusal = probe.capped.clientAbort();
            assertAll("a body whose attempt held its connection only after the deadline",
                    () -> assertTrue(quietBeforeTheFirstRead, "nothing ends the body before it is read"),
                    () -> assertInstanceOf(DispatchStage.RequestBodyDeadlineExceeded.class, refusal,
                            "the first read ends the body at its deadline"),
                    () -> assertSame(refusal, probe.failure.get(), "the refusal is propagated to the consumer"),
                    () -> assertEquals(1, probe.aborts.get(), "the upstream request is reset exactly once"),
                    () -> assertTrue(probe.source.paused(), "the inbound stream is paused and never read"),
                    () -> assertEquals(List.of(), probe.forwarded, "no body byte crosses to the upstream"),
                    () -> assertEquals(0, probe.time.pendingCount(), "nothing is pending once the body was ended"));
        }

        @Test
        @DisplayName("an attempt the dispatch no longer waits for stops its body's idle clock and deadline; nothing fires afterwards")
        void abandonedAttemptStopsTheClocksOfItsBody() {
            IdleProbe probe = deadlineProbe(DEADLINE_MILLIS);
            DispatchStage.UpstreamAttempt attempt = new DispatchStage.UpstreamAttempt();
            attempt.attachBody(probe.capped);
            probe.capped.startIdleClock();
            int pendingWhileWaitedFor = probe.time.pendingCount();

            attempt.abandon();
            int pendingOnceAbandoned = probe.time.pendingCount();
            probe.time.advanceMillis(2 * DEADLINE_MILLIS);
            probe.capped.resume();

            assertAll("the body of an abandoned attempt",
                    () -> assertEquals(2, pendingWhileWaitedFor,
                            "the idle clock and the deadline are pending while the attempt is waited for"),
                    () -> assertEquals(0, pendingOnceAbandoned, "abandoning the attempt cancels both"),
                    () -> assertNull(probe.failure.get(), "a stopped clock reports nothing to the consumer"),
                    () -> assertNull(probe.capped.clientAbort(), "a stopped clock records no refusal"),
                    () -> assertEquals(0, probe.aborts.get(), "a stopped clock resets no upstream request"),
                    () -> assertEquals(List.of(), probe.lateViolations, "a stopped clock reaches no callback"));
        }

        @Test
        @DisplayName("a body handed to an attempt the dispatch already stopped waiting for never starts its clocks")
        void bodyOfAnAlreadyAbandonedAttemptNeverStartsItsClocks() {
            IdleProbe probe = deadlineProbe(DEADLINE_MILLIS);
            DispatchStage.UpstreamAttempt attempt = new DispatchStage.UpstreamAttempt();
            attempt.abandon();

            attempt.attachBody(probe.capped);
            probe.capped.startIdleClock();
            probe.time.advanceMillis(2 * DEADLINE_MILLIS);

            assertAll("the body of an attempt abandoned before it was attached",
                    () -> assertEquals(0, probe.time.scheduledCount(), "neither clock is ever scheduled"),
                    () -> assertNull(probe.failure.get()),
                    () -> assertNull(probe.capped.clientAbort()),
                    () -> assertEquals(0, probe.aborts.get()));
        }

        /** A probe whose deadline passes {@code deadlineMillis} after its creation, with the fixed idle timeout. */
        private IdleProbe deadlineProbe(long deadlineMillis) {
            return new IdleProbe(TIMEOUT_MILLIS, DispatchStage.NO_DECLARED_LENGTH, OptionalLong.of(deadlineMillis));
        }

        /** Reads the paused body again the way {@code restart} names: a resume or a fetch. */
        private void readAgain(IdleProbe probe, String restart) {
            if ("resume".equals(restart)) {
                probe.capped.resume();
            } else {
                probe.capped.fetch(1);
            }
        }
    }

    /**
     * The late-end rule of the request-body deadline against the inbound transport itself. A byte-capped body
     * reads a live request while its clocks are driven by hand: the deadline's instant has passed and its timer
     * has not run when the client ends the request, so the expiry is claimed and carried out inside that
     * request's own end handler. Vert.x refuses to pause an HTTP/2 request there, because it has ended; an
     * expiry that paused the inbound stream first would throw before the upstream request was reset or the
     * failure propagated, and leave the dispatch to the guard's timeout. The stream fake of the cases above
     * accepts any call, so only a live request can show that.
     */
    @Nested
    @DisplayName("an inbound end past the request-body deadline over a live request")
    class LateEndOverALiveRequest {

        private static final long TIMEOUT_MILLIS = 1_000L;
        private static final long DEADLINE_MILLIS = 4 * TIMEOUT_MILLIS;

        private Vertx vertx;
        private HttpServer inbound;
        private @Nullable HttpClient client;
        /** The failure the body stream propagated to its consumer. */
        private final CompletableFuture<Throwable> propagated = new CompletableFuture<>();
        private final AtomicInteger aborts = new AtomicInteger();
        private final AtomicBoolean endPassedOn = new AtomicBoolean();

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            inbound = Awaits.connect(vertx.createHttpServer().requestHandler(this::readPastItsDeadline)
                    .listen(0, LoopbackHost.ADDRESS), "the inbound server to start listening");
        }

        @AfterEach
        void tearDown() throws Exception {
            if (client != null) {
                Awaits.teardown(client.close(), "the client to close");
            }
            Awaits.teardown(inbound.close(), "the inbound server to close");
            Awaits.teardown(vertx.close(), "Vert.x to close");
        }

        /**
         * Reads {@code request} through a byte-capped body whose deadline's instant has passed while its timer,
         * held by the {@link ManualTime}, never runs: only the request's end can act on the deadline. Everything
         * here, and every callback of the body, runs on the request's event loop.
         */
        private void readPastItsDeadline(HttpServerRequest request) {
            request.pause();
            ManualTime time = new ManualTime();
            DispatchStage.ByteCappedBodyStream capped = new DispatchStage.ByteCappedBodyStream(
                    DispatchStage.InboundBody.arm(request), BODY_CAP, DispatchStage.NO_DECLARED_LENGTH,
                    aborts::incrementAndGet, _ -> {
                        // The forwarded count feeds the retry gate, which this case does not exercise.
                    }, new DispatchStage.IdleClock(TIMEOUT_MILLIS, time, time::nanoTime),
                    OptionalLong.of(time.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MILLIS)));
            capped.handler(_ -> {
                // The body's bytes are not what this case observes.
            });
            capped.exceptionHandler(propagated::complete);
            capped.endHandler(_ -> endPassedOn.set(true));
            capped.startIdleClock();
            time.advanceMillisHoldingTimers(DEADLINE_MILLIS);
            capped.resume();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = HttpVersion.class, names = {"HTTP_1_1", "HTTP_2"})
        @DisplayName("the request's end, arriving after the deadline's instant, ends the body at its deadline from inside the end handler")
        void endOfALiveRequestPastItsDeadlineEndsTheBodyAtTheDeadline(HttpVersion version) throws Exception {
            client = vertx.createHttpClient(new HttpClientOptions().setProtocolVersion(version)
                    .setHttp2ClearTextUpgrade(false));
            HttpClientRequest upload = Awaits.connect(client.request(io.vertx.core.http.HttpMethod.POST,
                    inbound.actualPort(), LoopbackHost.ADDRESS, "/late"), "the upload to open");

            Awaits.connect(upload.end(Generators.letterStrings(2, 6).next()), "the upload to be sent and ended");

            Throwable refusal = Awaits.connect(propagated, "the body past its deadline to be ended");
            assertAll("a live " + version + " request ended after its deadline's instant, the deadline's timer pending",
                    () -> assertInstanceOf(DispatchStage.RequestBodyDeadlineExceeded.class, refusal,
                            "the end comes too late: the body is ended at its deadline"),
                    () -> assertEquals(1, aborts.get(), "the upstream request is reset exactly once"),
                    () -> assertFalse(endPassedOn.get(), "the late end is not passed on"));
        }
    }

    /**
     * The message clock of {@link DispatchStage.ByteCappedBodyStream} — the gRPC dispatch's bound on the
     * wait for each complete request message — driven by hand on a {@link ManualTime}. The probes arm no idle
     * clock and no deadline, so the message clock is the only timer a case can observe. One timer is pending
     * per gap: a complete message only records when the next gap began, and the timer re-arms for the
     * remainder when it fires early. Every case that pins the clock staying quiet also pins that it was
     * scheduled first, so a clock that never ran cannot pass for one that was stopped.
     */
    @Nested
    @DisplayName("message clock of the byte-capped body")
    class MessageClock {

        private static final long BOUND_MILLIS = 1_000L;

        @Test
        @DisplayName("no complete message within the bound: ends once as a RequestMessageTimeout, resets the upstream request and propagates the failure")
        void noCompleteMessageWithinTheBoundEndsTheBody() {
            IdleProbe probe = messageProbe(DispatchStage.NO_DECLARED_LENGTH);
            probe.capped.startMessageClock();

            probe.time.advanceMillis(BOUND_MILLIS - 1);
            boolean quietBeforeTheBound = probe.failure.get() == null && probe.aborts.get() == 0;
            probe.time.advanceMillis(1);
            GatewayException expiry = probe.capped.clientAbort();
            probe.time.advanceMillis(5 * BOUND_MILLIS);

            assertAll("a body with no complete message within the message bound",
                    () -> assertTrue(quietBeforeTheBound, "nothing may fire before the whole bound passed"),
                    () -> assertInstanceOf(DispatchStage.RequestMessageTimeout.class, expiry,
                            "the expiry is recorded as the stream's client abort"),
                    () -> assertEquals(EventType.INBOUND_BODY_ABORTED,
                            assertInstanceOf(GatewayException.class, expiry).getEventType()),
                    () -> assertSame(expiry, probe.failure.get(), "the expiry is propagated to the consumer"),
                    () -> assertEquals(1, probe.aborts.get(), "the upstream request is reset exactly once"),
                    () -> assertTrue(probe.source.paused(), "the inbound stream is paused by the expiry"),
                    () -> assertEquals(1, probe.time.scheduledCount(), "the clock is one timer, never re-armed"),
                    () -> assertEquals(0, probe.time.pendingCount(), "nothing is pending after the expiry"));
        }

        @Test
        @DisplayName("a complete message restarts the gap: the timer due at the old bound re-arms once, and the body is ended one full bound after the message")
        void completeMessageRestartsTheGap() {
            IdleProbe probe = messageProbe(DispatchStage.NO_DECLARED_LENGTH);
            probe.capped.startMessageClock();
            probe.time.advanceMillis(BOUND_MILLIS - 1);

            probe.source.emit(grpcMessage(Generators.letterStrings(2, 6).next()));
            int pendingAfterTheMessage = probe.time.pendingCount();
            int scheduledAfterTheMessage = probe.time.scheduledCount();
            probe.time.advanceMillis(1);
            boolean quietAtTheOldBound = probe.failure.get() == null;
            int scheduledAtTheOldBound = probe.time.scheduledCount();
            probe.time.advanceMillis(BOUND_MILLIS - 2);
            boolean quietJustBeforeTheNewBound = probe.failure.get() == null;
            probe.time.advanceMillis(1);

            GatewayException expiry = probe.capped.clientAbort();
            assertAll("a body whose complete message arrived just before the bound, then nothing",
                    () -> assertEquals(1, pendingAfterTheMessage,
                            "the gap's one timer stays pending: the message only records when the next gap began"),
                    () -> assertEquals(1, scheduledAfterTheMessage, "the message itself schedules nothing"),
                    () -> assertTrue(quietAtTheOldBound, "the timer due at the old bound does not end the body"),
                    () -> assertEquals(2, scheduledAtTheOldBound, "it re-arms once, for the remainder of the new gap"),
                    () -> assertTrue(quietJustBeforeTheNewBound,
                            "nothing ends the body before one full bound after the message"),
                    () -> assertInstanceOf(DispatchStage.RequestMessageTimeout.class, expiry,
                            "one full bound after the last complete message the body is ended"),
                    () -> assertEquals(1, probe.forwarded.size(), "the message was forwarded"),
                    () -> assertEquals(1, probe.aborts.get(), "the upstream request is reset exactly once"));
        }

        @Test
        @DisplayName("chunks and messages within one gap schedule nothing: one timer per gap, never one per chunk")
        void chunksWithinOneGapKeepOneTimer() {
            IdleProbe probe = messageProbe(DispatchStage.NO_DECLARED_LENGTH);
            Buffer split = grpcMessage(Generators.letterStrings(4, 12).next());
            Buffer twoMessages = grpcMessage(Generators.letterStrings(2, 6).next())
                    .appendBuffer(grpcMessage(Generators.letterStrings(2, 6).next()));
            probe.capped.startMessageClock();

            probe.time.advanceMillis(BOUND_MILLIS / 4);
            probe.source.emit(twoMessages);
            probe.time.advanceMillis(BOUND_MILLIS / 4);
            probe.source.emit(split.slice(0, GRPC_PREFIX_LENGTH + 1));
            probe.time.advanceMillis(BOUND_MILLIS / 4);
            probe.source.emit(split.slice(GRPC_PREFIX_LENGTH + 1, split.length()));

            assertAll("three chunks completing three messages within one gap",
                    () -> assertEquals(3, probe.forwarded.size(), "every chunk is forwarded"),
                    () -> assertEquals(1, probe.time.scheduledCount(), "no chunk and no message schedules a timer"),
                    () -> assertEquals(1, probe.time.pendingCount(), "the gap's one timer is still pending"),
                    () -> assertNull(probe.capped.clientAbort()),
                    () -> assertEquals(0, probe.aborts.get()));
        }

        @Test
        @DisplayName("a steady sender, each gap shorter than the bound and the whole body far longer, is never ended by it")
        void steadySenderIsNeverEndedByTheBound() {
            IdleProbe probe = messageProbe(DispatchStage.NO_DECLARED_LENGTH);
            int messages = 10;
            probe.capped.startMessageClock();

            for (int message = 0; message < messages; message++) {
                probe.time.advanceMillis(BOUND_MILLIS - 100);
                probe.source.emit(grpcMessage(Generators.letterStrings(2, 6).next()));
            }

            assertAll("a body sending a complete message every %d ms for %d ms".formatted(BOUND_MILLIS - 100,
                    messages * (BOUND_MILLIS - 100)),
                    () -> assertEquals(messages, probe.forwarded.size(), "every message is forwarded"),
                    () -> assertNull(probe.capped.clientAbort(), "no gap reached the bound"),
                    () -> assertNull(probe.failure.get()),
                    () -> assertEquals(0, probe.aborts.get()),
                    () -> assertEquals(1, probe.time.pendingCount(), "one timer is pending at a time"));
        }

        @ParameterizedTest(name = "restarted by {0}")
        @ValueSource(strings = {"resume", "fetch"})
        @DisplayName("paused by the consumer: the upstream's backpressure is not counted, and the gap restarts when the flow restarts")
        void pauseIsNotCounted(String restart) {
            IdleProbe probe = messageProbe(DispatchStage.NO_DECLARED_LENGTH);
            probe.capped.startMessageClock();
            probe.capped.pause();

            probe.time.advanceMillis(5 * BOUND_MILLIS);
            boolean quietWhilePaused = probe.failure.get() == null && probe.aborts.get() == 0;
            int pendingWhilePaused = probe.time.pendingCount();
            if ("resume".equals(restart)) {
                probe.capped.resume();
            } else {
                probe.capped.fetch(1);
            }
            probe.time.advanceMillis(BOUND_MILLIS - 1);
            boolean quietJustBeforeTheBound = probe.failure.get() == null;
            probe.time.advanceMillis(1);

            assertAll("a body paused by its consumer",
                    () -> assertTrue(quietWhilePaused, "a pause is never charged to the client"),
                    () -> assertEquals(0, pendingWhilePaused, "the timer that fired during the pause did not re-arm"),
                    () -> assertTrue(quietJustBeforeTheBound, "the gap restarts from the moment the flow restarts"),
                    () -> assertInstanceOf(DispatchStage.RequestMessageTimeout.class, probe.capped.clientAbort()),
                    () -> assertEquals(1, probe.aborts.get()));
        }

        @Test
        @DisplayName("a chunk of length zero is not a message: the clock still ends the body at the bound")
        void emptyChunkIsNotAMessage() {
            IdleProbe probe = messageProbe(DispatchStage.NO_DECLARED_LENGTH);
            probe.capped.startMessageClock();

            probe.source.emit(Buffer.buffer());
            int pendingAfterTheEmptyChunk = probe.time.pendingCount();
            probe.time.advanceMillis(BOUND_MILLIS);

            assertAll("an empty chunk before the bound",
                    () -> assertEquals(1, pendingAfterTheEmptyChunk, "the empty chunk leaves the timer pending"),
                    () -> assertInstanceOf(DispatchStage.RequestMessageTimeout.class, probe.capped.clientAbort()),
                    () -> assertEquals(1, probe.aborts.get()));
        }

        /**
         * Once a complete message has re-armed the clock, each of the three ways the clock ends for good — the
         * inbound end, the attempt's send completing, and an attempt the dispatch no longer waits for — cancels
         * the re-armed timer, so later silence is never ended by the bound.
         */
        @ParameterizedTest(name = "stopped by {0}")
        @ValueSource(strings = {"the inbound end", "the completed send", "the abandoned attempt"})
        @DisplayName("a clock re-armed after a complete message stops for good at the body end, the send completion and the abandon")
        void reArmedClockStopsForGood(String stop) {
            IdleProbe probe = messageProbe(DispatchStage.NO_DECLARED_LENGTH);
            DispatchStage.UpstreamAttempt attempt = new DispatchStage.UpstreamAttempt();
            attempt.attachBody(probe.capped);
            probe.capped.startMessageClock();
            probe.time.advanceMillis(BOUND_MILLIS / 2);
            probe.source.emit(grpcMessage(Generators.letterStrings(2, 6).next()));
            probe.time.advanceMillis(BOUND_MILLIS / 2);
            int scheduledBeforeTheStop = probe.time.scheduledCount();
            int pendingBeforeTheStop = probe.time.pendingCount();

            switch (stop) {
                case "the inbound end" -> probe.source.end();
                case "the completed send" -> probe.capped.stopMessageClock();
                default -> attempt.abandon();
            }
            int pendingAfterTheStop = probe.time.pendingCount();
            probe.time.advanceMillis(5 * BOUND_MILLIS);

            assertAll("a re-armed message clock stopped by " + stop,
                    () -> assertEquals(2, scheduledBeforeTheStop, "the clock re-armed for the gap after the message"),
                    () -> assertEquals(1, pendingBeforeTheStop, "the re-armed timer is pending before the stop"),
                    () -> assertEquals(0, pendingAfterTheStop, "the stop cancels it"),
                    () -> assertNull(probe.capped.clientAbort(), "a stopped clock records no refusal"),
                    () -> assertNull(probe.failure.get(), "a stopped clock reports nothing to the consumer"),
                    () -> assertEquals(0, probe.aborts.get(), "a stopped clock resets no upstream request"));
        }

        @Test
        @DisplayName("a body that ends without any byte stops the clock: the end is passed on and nothing is refused")
        void inboundEndStopsTheClock() {
            IdleProbe probe = messageProbe(DispatchStage.NO_DECLARED_LENGTH);
            probe.capped.startMessageClock();
            int scheduledOnStart = probe.time.scheduledCount();

            probe.source.end();
            int pendingAfterTheEnd = probe.time.pendingCount();
            probe.time.advanceMillis(5 * BOUND_MILLIS);

            assertAll("a body that ended without a byte",
                    () -> assertEquals(1, scheduledOnStart, "the clock was armed when it was started"),
                    () -> assertEquals(0, pendingAfterTheEnd, "the end cancels the pending timer"),
                    () -> assertTrue(probe.ended.get(), "the end is passed on"),
                    () -> assertNull(probe.failure.get(), "an ended body is never ended by the bound"),
                    () -> assertNull(probe.capped.clientAbort()),
                    () -> assertEquals(0, probe.aborts.get()));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(EarlierAbort.class)
        @DisplayName("an abort claimed first stops the clock: a later expiry changes nothing")
        void earlierAbortKeepsItsClaim(EarlierAbort earlier) {
            IdleProbe probe = messageProbe(earlier.declaredLength);
            probe.capped.startMessageClock();
            int scheduledOnStart = probe.time.scheduledCount();

            earlier.trigger(probe);
            GatewayException claimed = probe.capped.clientAbort();
            int pendingAfterTheClaim = probe.time.pendingCount();
            probe.time.advanceMillis(5 * BOUND_MILLIS);

            assertAll(earlier.name(),
                    () -> assertEquals(1, scheduledOnStart, "the clock was armed when it was started"),
                    () -> assertEquals(earlier.expected, claimed == null ? null : claimed.getEventType(),
                            "the earlier trigger owns the abort claim"),
                    () -> assertEquals(0, pendingAfterTheClaim, "the earlier abort cancels the pending timer"),
                    () -> assertSame(claimed, probe.capped.clientAbort(), "the expiry does not replace the claim"),
                    () -> assertEquals(1, probe.aborts.get(), "the abort action runs once, for the earlier trigger"),
                    () -> assertFalse(probe.failure.get() instanceof DispatchStage.RequestMessageTimeout,
                            "no message timeout is propagated after the earlier abort"));
        }

        @Test
        @DisplayName("the attempt's send completing stops the clock: nothing fires and nothing is refused at the hand-over")
        void completedSendStopsTheClock() {
            IdleProbe probe = messageProbe(DispatchStage.NO_DECLARED_LENGTH);
            probe.capped.startMessageClock();
            int scheduledOnStart = probe.time.scheduledCount();

            probe.capped.stopMessageClock();
            int pendingOnceStopped = probe.time.pendingCount();
            probe.time.advanceMillis(5 * BOUND_MILLIS);
            GatewayException atTheHandOver = probe.handOver();

            assertAll("a body whose attempt's send completed before a byte arrived",
                    () -> assertEquals(1, scheduledOnStart, "the clock was armed when it was started"),
                    () -> assertEquals(0, pendingOnceStopped, "the stop cancels the pending timer"),
                    () -> assertNull(atTheHandOver, "nothing is refused when the response is handed over"),
                    () -> assertNull(probe.capped.clientAbort()),
                    () -> assertNull(probe.failure.get()),
                    () -> assertEquals(0, probe.aborts.get()),
                    () -> assertEquals(List.of(), probe.lateViolations, "the clock never reaches the late callback"));
        }

        @Test
        @DisplayName("an attempt the dispatch no longer waits for stops the clock of its body: nothing fires afterwards")
        void abandonedAttemptStopsTheClock() {
            IdleProbe probe = messageProbe(DispatchStage.NO_DECLARED_LENGTH);
            DispatchStage.UpstreamAttempt attempt = new DispatchStage.UpstreamAttempt();
            attempt.attachBody(probe.capped);
            probe.capped.startMessageClock();
            int pendingWhileWaitedFor = probe.time.pendingCount();

            attempt.abandon();
            int pendingOnceAbandoned = probe.time.pendingCount();
            probe.time.advanceMillis(5 * BOUND_MILLIS);

            assertAll("the message clock of an abandoned attempt",
                    () -> assertEquals(1, pendingWhileWaitedFor, "the clock is pending while the attempt is waited for"),
                    () -> assertEquals(0, pendingOnceAbandoned, "abandoning the attempt cancels it"),
                    () -> assertNull(probe.capped.clientAbort(), "a stopped clock records no refusal"),
                    () -> assertNull(probe.failure.get(), "a stopped clock reports nothing to the consumer"),
                    () -> assertEquals(0, probe.aborts.get(), "a stopped clock resets no upstream request"));
        }

        @Test
        @DisplayName("a body handed to an attempt the dispatch already stopped waiting for never starts the clock")
        void bodyOfAnAlreadyAbandonedAttemptNeverStartsTheClock() {
            IdleProbe probe = messageProbe(DispatchStage.NO_DECLARED_LENGTH);
            DispatchStage.UpstreamAttempt attempt = new DispatchStage.UpstreamAttempt();
            attempt.abandon();

            attempt.attachBody(probe.capped);
            probe.capped.startMessageClock();
            probe.time.advanceMillis(5 * BOUND_MILLIS);

            assertAll("the body of an attempt abandoned before it was attached",
                    () -> assertEquals(0, probe.time.scheduledCount(), "the clock is never scheduled"),
                    () -> assertNull(probe.capped.clientAbort()),
                    () -> assertEquals(0, probe.aborts.get()));
        }

        @Test
        @DisplayName("a completing chunk arriving after the expiry is dropped: the expiry keeps the claim")
        void completingChunkAfterTheExpiryIsDropped() {
            IdleProbe probe = messageProbe(DispatchStage.NO_DECLARED_LENGTH);
            Buffer message = grpcMessage(Generators.letterStrings(2, 6).next());
            probe.capped.startMessageClock();
            probe.source.emit(message.slice(0, GRPC_PREFIX_LENGTH));
            probe.time.advanceMillis(BOUND_MILLIS);
            GatewayException expiry = probe.capped.clientAbort();

            probe.source.emit(message.slice(GRPC_PREFIX_LENGTH, message.length()));

            assertAll("a message completed only after the expiry",
                    () -> assertInstanceOf(DispatchStage.RequestMessageTimeout.class, expiry),
                    () -> assertSame(expiry, probe.capped.clientAbort(), "the expiry keeps the claim"),
                    () -> assertEquals(1, probe.forwarded.size(),
                            "only the prefix crossed; the completing chunk never reaches the upstream"),
                    () -> assertEquals(1, probe.aborts.get()));
        }

        /**
         * The expiry's side of the race against a completing chunk, from the other end: the completing chunk
         * arrives one millisecond before the bound, so the completion starts the next gap and the timer that
         * would have ended the body at the bound re-arms for that gap instead.
         */
        @Test
        @DisplayName("a completing chunk just before the bound wins: the timer due at the bound re-arms instead of ending the body")
        void completingChunkJustBeforeTheBoundWins() {
            IdleProbe probe = messageProbe(DispatchStage.NO_DECLARED_LENGTH);
            Buffer message = grpcMessage(Generators.letterStrings(2, 6).next());
            probe.capped.startMessageClock();
            probe.source.emit(message.slice(0, GRPC_PREFIX_LENGTH));
            probe.time.advanceMillis(BOUND_MILLIS - 1);

            probe.source.emit(message.slice(GRPC_PREFIX_LENGTH, message.length()));
            probe.time.advanceMillis(BOUND_MILLIS - 1);

            assertAll("a message completed just before the bound",
                    () -> assertEquals(2, probe.forwarded.size(), "both parts cross to the upstream"),
                    () -> assertNull(probe.capped.clientAbort(), "the expiry never claims the abort"),
                    () -> assertNull(probe.failure.get()),
                    () -> assertEquals(0, probe.aborts.get()),
                    () -> assertEquals(2, probe.time.scheduledCount(),
                            "the timer due at the bound re-armed once, for the next gap"),
                    () -> assertEquals(1, probe.time.pendingCount(), "the next gap's timer is pending"));
        }

        @Test
        @DisplayName("an expiry before the hand-over is the dispatch's own refusal, never a late one")
        void expiryBeforeTheHandOverIsReturnedByIt() {
            IdleProbe probe = messageProbe(DispatchStage.NO_DECLARED_LENGTH);
            probe.capped.startMessageClock();
            probe.time.advanceMillis(BOUND_MILLIS);

            GatewayException returned = probe.handOver();

            assertAll("an expiry before the hand-over",
                    () -> assertInstanceOf(DispatchStage.RequestMessageTimeout.class, returned),
                    () -> assertSame(probe.capped.clientAbort(), returned, "the hand-over reports the expiry"),
                    () -> assertEquals(List.of(), probe.lateViolations, "the late-violation callback is not run"));
        }

        @Test
        @DisplayName("NO_FIRST_MESSAGE_TIMEOUT, the message bound of every HTTP dispatch: the clock is never armed and nothing is scheduled")
        void unarmedClockSchedulesNothing() {
            IdleProbe probe = new IdleProbe(DispatchStage.NO_BODY_IDLE_TIMEOUT, DispatchStage.NO_DECLARED_LENGTH,
                    OptionalLong.empty(), DispatchStage.NO_FIRST_MESSAGE_TIMEOUT);

            probe.capped.startMessageClock();
            probe.time.advanceMillis(60 * BOUND_MILLIS);
            probe.source.emit(Buffer.buffer(Generators.letterStrings(2, 6).next()));
            probe.capped.stopMessageClock();

            assertAll("an unarmed message clock",
                    () -> assertEquals(0, probe.time.scheduledCount(), "nothing is ever scheduled"),
                    () -> assertEquals(1, probe.forwarded.size(), "the late first byte is forwarded"),
                    () -> assertNull(probe.capped.clientAbort()),
                    () -> assertEquals(0, probe.aborts.get()));
        }

        @Test
        @DisplayName("a negative message bound is refused when the stage is built; NO_FIRST_MESSAGE_TIMEOUT is accepted")
        void negativeBoundIsRefused() {
            UpstreamClients noClients = new UpstreamClients(Map.of(), Map.of());
            UpstreamFailureMapper failureMapper = new UpstreamFailureMapper(new GatewayEventCounter());

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new DispatchStage(noClients, BODY_CAP, DispatchStage.NO_BODY_IDLE_TIMEOUT,
                            DispatchStage.NO_BODY_DEADLINE, -1L, DispatchStage.NO_PRE_RESPONSE_CAP,
                            DispatchStage.NO_HAND_OVER_GRACE, DispatchStage.NO_UPSTREAM_STALL, failureMapper));

            assertAll("the message bound of a stage",
                    () -> assertTrue(refused.getMessage().contains("messageTimeoutMillis"),
                            "the refusal names the bound"),
                    () -> assertDoesNotThrow(() -> new DispatchStage(noClients, BODY_CAP,
                                    DispatchStage.NO_BODY_IDLE_TIMEOUT, DispatchStage.NO_BODY_DEADLINE,
                                    DispatchStage.NO_FIRST_MESSAGE_TIMEOUT, DispatchStage.NO_PRE_RESPONSE_CAP,
                                    DispatchStage.NO_HAND_OVER_GRACE, DispatchStage.NO_UPSTREAM_STALL, failureMapper),
                            "the unarmed bound is a valid bound"));
        }

        /** A probe whose only clock is the message clock, bounded by {@link #BOUND_MILLIS}. */
        private IdleProbe messageProbe(long declaredLength) {
            return new IdleProbe(DispatchStage.NO_BODY_IDLE_TIMEOUT, declaredLength, OptionalLong.empty(),
                    BOUND_MILLIS);
        }
    }

    /**
     * What restarts the message clock's gap: on a gRPC body only a whole length-prefixed message — the 5-byte
     * prefix and the payload length it declares — forwarded, however the chunks split it. A body that forwards
     * part of a message and stalls is still ended at the bound, counted from the last complete message.
     * Every probe arms only the message clock.
     */
    @Nested
    @DisplayName("complete-message restart of the message clock")
    class CompleteMessage {

        private static final long BOUND_MILLIS = 1_000L;

        @Test
        @DisplayName("part of a first message then silence: ends at the bound as a RequestMessageTimeout and resets the upstream request")
        void partialFirstMessageThenSilenceEndsTheBody() {
            IdleProbe probe = probe();
            Buffer message = grpcMessage(Generators.letterStrings(4, 12).next());
            probe.capped.startMessageClock();
            probe.time.advanceMillis(BOUND_MILLIS - 1);

            probe.source.emit(message.slice(0, GRPC_PREFIX_LENGTH + 1));
            int pendingAfterThePart = probe.time.pendingCount();
            probe.time.advanceMillis(1);

            GatewayException expiry = probe.capped.clientAbort();
            assertAll("a first message that arrived only in part, just before the bound",
                    () -> assertEquals(1, pendingAfterThePart, "part of a message leaves the clock pending"),
                    () -> assertEquals(1, probe.forwarded.size(), "the part is forwarded as it arrives"),
                    () -> assertInstanceOf(DispatchStage.RequestMessageTimeout.class, expiry,
                            "a stalled partial message is the client's abort: its part did not restart the gap"),
                    () -> assertSame(expiry, probe.failure.get(), "the expiry is propagated to the consumer"),
                    () -> assertEquals(1, probe.aborts.get(), "the upstream request is reset exactly once"),
                    () -> assertTrue(probe.source.paused(), "the inbound stream is paused by the expiry"));
        }

        @ParameterizedTest(name = "prefix split after byte {0}")
        @ValueSource(ints = {1, 3})
        @DisplayName("a prefix split across chunks is read across them: the gap restarts only once the message is complete")
        void prefixSplitAcrossChunksRestartsTheGapAtTheCompleteMessage(int split) {
            IdleProbe probe = probe();
            Buffer message = grpcMessage(Generators.letterStrings(4, 12).next());
            probe.capped.startMessageClock();
            probe.time.advanceMillis(BOUND_MILLIS / 2);

            probe.source.emit(message.slice(0, split));
            probe.time.advanceMillis(BOUND_MILLIS / 2 - 1);
            probe.source.emit(message.slice(split, message.length()));

            assertGapRestartedByTheLastChunk(probe, message, "a first message whose prefix straddles two chunks");
        }

        @Test
        @DisplayName("a payload split across chunks restarts the gap only with its last byte")
        void payloadSplitAcrossChunksRestartsTheGapWithItsLastByte() {
            IdleProbe probe = probe();
            Buffer message = grpcMessage(Generators.letterStrings(4, 12).next());
            int lastByte = message.length() - 1;
            probe.capped.startMessageClock();

            probe.source.emit(message.slice(0, lastByte));
            probe.time.advanceMillis(BOUND_MILLIS - 1);
            probe.source.emit(message.slice(lastByte, message.length()));

            assertGapRestartedByTheLastChunk(probe, message, "a first message whose payload straddles two chunks");
        }

        @Test
        @DisplayName("a zero-length message is complete at its prefix")
        void zeroLengthMessageCompletesAtItsPrefix() {
            IdleProbe probe = probe();
            Buffer empty = grpcMessage("");
            probe.capped.startMessageClock();
            probe.time.advanceMillis(BOUND_MILLIS - 1);

            probe.source.emit(empty);

            assertGapRestartedByTheLastChunk(probe, empty, "an empty first message");
        }

        /**
         * Pins that the chunk just emitted, one millisecond before the bound counted from the clock's start,
         * completed {@code message}: the timer due at that bound re-arms instead of ending the body, and the body
         * is ended exactly one bound after that chunk.
         */
        private void assertGapRestartedByTheLastChunk(IdleProbe probe, Buffer message, String heading) {
            probe.time.advanceMillis(BOUND_MILLIS - 1);
            boolean quietUntilOneBoundAfterTheCompletion = probe.capped.clientAbort() == null;
            probe.time.advanceMillis(1);

            assertAll(heading,
                    () -> assertEquals(message, joined(probe.forwarded), "every byte is forwarded unchanged"),
                    () -> assertTrue(quietUntilOneBoundAfterTheCompletion,
                            "the completing chunk restarted the gap: the original bound passes quietly"),
                    () -> assertInstanceOf(DispatchStage.RequestMessageTimeout.class, probe.capped.clientAbort(),
                            "with no further message, the body is ended one bound after the completing chunk"),
                    () -> assertEquals(1, probe.aborts.get()));
        }

        /** A probe whose only clock is the message clock, bounded by {@link #BOUND_MILLIS}. */
        private IdleProbe probe() {
            return new IdleProbe(DispatchStage.NO_BODY_IDLE_TIMEOUT, DispatchStage.NO_DECLARED_LENGTH,
                    OptionalLong.empty(), BOUND_MILLIS);
        }

        /** @return the forwarded chunks joined in order */
        private Buffer joined(List<Buffer> chunks) {
            Buffer all = Buffer.buffer();
            chunks.forEach(all::appendBuffer);
            return all;
        }
    }

    /**
     * The frame reader behind the message clock, {@link DispatchStage.GrpcMessageFrames}, on its own: how many
     * messages each chunk completes, however the chunks split the 5-byte prefixes and the payloads. The cases
     * above pin what a completed message does to the clock; these pin the count the reader returns.
     */
    @Nested
    @DisplayName("gRPC message frames read as their chunks cross")
    class MessageFrames {

        private final DispatchStage.GrpcMessageFrames frames = new DispatchStage.GrpcMessageFrames();

        @Test
        @DisplayName("counts every message a chunk completes and carries the one it only starts into the next chunk")
        void countsEveryMessageAChunkCompletes() {
            Buffer started = grpcMessage(Generators.letterStrings(4, 12).next());
            int split = GRPC_PREFIX_LENGTH + 1;
            Buffer twoMessagesAndAStart = grpcMessage(Generators.letterStrings(2, 6).next())
                    .appendBuffer(grpcMessage(""))
                    .appendBuffer(started.slice(0, split));

            int completedByTheFirstChunk = frames.completedBy(twoMessagesAndAStart);
            int completedByAnEmptyChunk = frames.completedBy(Buffer.buffer());
            int completedByTheRest = frames.completedBy(started.slice(split, started.length()));

            assertAll("a chunk carrying two whole messages and the start of a third, then the third's rest",
                    () -> assertEquals(2, completedByTheFirstChunk,
                            "both whole messages, the second of length zero, are complete; the started one is not"),
                    () -> assertEquals(0, completedByAnEmptyChunk, "a chunk without a byte completes nothing"),
                    () -> assertEquals(1, completedByTheRest, "the rest of the third message completes it"));
        }

        @Test
        @DisplayName("a message arriving one byte per chunk is complete only at its last byte")
        void messageSplitByteByByteCompletesAtItsLastByte() {
            Buffer message = grpcMessage(Generators.letterStrings(2, 6).next());
            List<Integer> completedPerByte = new ArrayList<>();

            for (int index = 0; index < message.length(); index++) {
                completedPerByte.add(frames.completedBy(message.slice(index, index + 1)));
            }

            List<Integer> onlyTheLastByte = new ArrayList<>(Collections.nCopies(message.length() - 1, 0));
            onlyTheLastByte.add(1);
            assertEquals(onlyTheLastByte, completedPerByte,
                    "no prefix byte and no payload byte but the last completes the message");
        }

        /**
         * The payload length is the unsigned big-endian integer of the four bytes after the compressed flag. A
         * length above 255 spans more than one of them, and a set flag must not be read as part of it.
         */
        @Test
        @DisplayName("reads the payload length from the four bytes after the compressed flag, most significant first")
        void readsThePayloadLengthFromTheFourBytesAfterTheFlag() {
            int payloadLength = Generators.integers(256, 1024).next();
            Buffer compressed = Buffer.buffer().appendByte((byte) 1).appendInt(payloadLength)
                    .appendBytes(new byte[payloadLength]);
            int lastByte = compressed.length() - 1;

            int completedShortOfTheLastByte = frames.completedBy(compressed.slice(0, lastByte));
            int completedByTheLastByte = frames.completedBy(compressed.slice(lastByte, compressed.length()));

            assertAll("a compressed message of " + payloadLength + " payload bytes",
                    () -> assertEquals(0, completedShortOfTheLastByte, "one payload byte is still owed"),
                    () -> assertEquals(1, completedByTheLastByte, "the last payload byte completes the message"));
        }
    }

    /**
     * The pre-response deadline of {@link DispatchStage.ByteCappedBodyStream}: the request-body deadline armed
     * with the message clock when no idle clock is armed — the gRPC dispatch's bound on a request stream still
     * open before the upstream answers — driven by hand on a {@link ManualTime}. It is armed when the message
     * clock starts and ends with it when the attempt's send completes, so it fires only before the hand-over.
     */
    @Nested
    @DisplayName("pre-response deadline of the byte-capped body")
    class PreResponseDeadline {

        private static final long BOUND_MILLIS = 1_000L;
        private static final long DEADLINE_MILLIS = 2_500L;
        /** The upstream stall threshold: equal to the bound, as the production threshold is. */
        private static final long STALL_MILLIS = BOUND_MILLIS;
        /** The gap between the steady sender's complete messages: shorter than the bound. */
        private static final long STEADY_GAP_MILLIS = 600L;
        /**
         * When the threshold-edge cases' upstream stops taking data: after three steady messages, before the
         * deadline and less than the stall threshold before it, so the deadline passes early in the pause and only
         * the pause's length at the read decides.
         */
        private static final long PAUSED_AT_MILLIS = 3 * STEADY_GAP_MILLIS;

        @Test
        @DisplayName("a steady sender that never ends its stream is ended at the deadline as RequestBodyDeadlineExceeded, before the guard")
        void steadySenderIsEndedAtTheDeadline() {
            IdleProbe probe = deadlineProbe();
            probe.capped.startMessageClock();
            int scheduledOnStart = probe.time.scheduledCount();

            long sent = sendSteadily(probe, DEADLINE_MILLIS - 1);
            probe.time.advanceMillis(DEADLINE_MILLIS - 1 - sent);
            boolean quietBeforeTheDeadline = probe.failure.get() == null && probe.aborts.get() == 0;
            probe.time.advanceMillis(1);

            GatewayException refusal = probe.capped.clientAbort();
            assertAll("a request stream still open at its pre-response deadline",
                    () -> assertEquals(2, scheduledOnStart, "the message timer and the deadline are armed together"),
                    () -> assertTrue(quietBeforeTheDeadline, "no gap reached the bound, and the deadline had not passed"),
                    () -> assertInstanceOf(DispatchStage.RequestBodyDeadlineExceeded.class, refusal,
                            "the open request stream is ended as the client's"),
                    () -> assertEquals(EventType.REQUEST_BODY_TIMEOUT,
                            assertInstanceOf(GatewayException.class, refusal).getEventType()),
                    () -> assertSame(refusal, probe.failure.get(), "the refusal is propagated to the consumer"),
                    () -> assertEquals(1, probe.aborts.get(), "the upstream request is reset exactly once"),
                    () -> assertTrue(probe.source.paused(), "the inbound stream is paused by the refusal"),
                    () -> assertEquals(0, probe.time.pendingCount(), "the message timer is cancelled with it"));
        }

        @Test
        @DisplayName("an expiry before the hand-over is the dispatch's own refusal, never a late one")
        void expiryBeforeTheHandOverIsReturnedByIt() {
            IdleProbe probe = deadlineProbe();
            probe.capped.startMessageClock();
            long sent = sendSteadily(probe, DEADLINE_MILLIS);
            probe.time.advanceMillis(DEADLINE_MILLIS - sent);

            GatewayException returned = probe.handOver();

            assertAll("a pre-response deadline that passed before the hand-over",
                    () -> assertInstanceOf(DispatchStage.RequestBodyDeadlineExceeded.class, returned),
                    () -> assertSame(probe.capped.clientAbort(), returned, "the hand-over reports the refusal"),
                    () -> assertEquals(List.of(), probe.lateViolations, "the late-violation callback is not run"));
        }

        @Test
        @DisplayName("the attempt's send completing stops the deadline: it never fires after the hand-over")
        void completedSendStopsTheDeadline() {
            IdleProbe probe = deadlineProbe();
            probe.capped.startMessageClock();
            int pendingWhileOpen = probe.time.pendingCount();

            probe.capped.stopMessageClock();
            int pendingOnceSent = probe.time.pendingCount();
            GatewayException atTheHandOver = probe.handOver();
            probe.time.advanceMillis(2 * DEADLINE_MILLIS);
            probe.capped.pause();
            probe.capped.resume();

            assertAll("a request stream whose upstream answered before the deadline",
                    () -> assertEquals(2, pendingWhileOpen, "the message timer and the deadline are pending"),
                    () -> assertEquals(0, pendingOnceSent, "the send completion cancels both"),
                    () -> assertNull(atTheHandOver, "nothing is refused when the response is handed over"),
                    () -> assertNull(probe.capped.clientAbort(), "the deadline never ends the answered stream"),
                    () -> assertNull(probe.failure.get()),
                    () -> assertEquals(0, probe.aborts.get()),
                    () -> assertEquals(List.of(), probe.lateViolations, "the deadline never reaches the late callback"));
        }

        /**
         * The race between the send completing and the deadline, from both ends: a send completing one
         * millisecond before the deadline takes it, so the timer due then reports nothing; a deadline that passed
         * first keeps its claim, and the send completing afterwards changes nothing.
         */
        @ParameterizedTest(name = "send completes {0} ms after the deadline")
        @ValueSource(longs = {-1L, 1L})
        @DisplayName("the deadline loses cleanly to a send that completes first, and keeps its claim against one that completes later")
        void raceAgainstTheSendCompletion(long completionAfterTheDeadline) {
            IdleProbe probe = deadlineProbe();
            probe.capped.startMessageClock();
            long sent = sendSteadily(probe, DEADLINE_MILLIS - 1);
            probe.time.advanceMillis(DEADLINE_MILLIS + completionAfterTheDeadline - sent);

            probe.capped.stopMessageClock();
            probe.time.advanceMillis(2 * DEADLINE_MILLIS);
            GatewayException atTheHandOver = probe.handOver();

            GatewayException claimed = probe.capped.clientAbort();
            if (completionAfterTheDeadline < 0) {
                assertAll("a send completing just before the deadline",
                        () -> assertNull(claimed, "the deadline never claims the abort"),
                        () -> assertNull(atTheHandOver),
                        () -> assertNull(probe.failure.get()),
                        () -> assertEquals(0, probe.aborts.get()),
                        () -> assertEquals(0, probe.time.pendingCount(), "the completion cancelled every timer"));
            } else {
                assertAll("a send completing just after the deadline",
                        () -> assertInstanceOf(DispatchStage.RequestBodyDeadlineExceeded.class, claimed),
                        () -> assertSame(claimed, atTheHandOver, "the hand-over reports the deadline's refusal"),
                        () -> assertEquals(1, probe.aborts.get(), "the upstream request is reset exactly once"),
                        () -> assertEquals(List.of(), probe.lateViolations, "the refusal is never a late one"));
            }
        }

        /**
         * A client whose stream the consumer paused because the upstream is not taking data is not ended by the
         * message bound — the pause is the upstream's, not the client's — and the deadline that passes during
         * the pause is the upstream's too: by the time the upstream reads again the pause has lasted far longer
         * than the stall threshold. It ends the body the moment the upstream reads it again, as the upstream's
         * timeout, never as a client abort.
         */
        @Test
        @DisplayName("paused by the upstream when the deadline passes: never ended by the bound, ended at the next read as the upstream's stall")
        void pausedByTheUpstreamAtTheDeadlineIsTheUpstreamsStall() {
            IdleProbe probe = deadlineProbe();
            probe.capped.startMessageClock();
            probe.time.advanceMillis(BOUND_MILLIS / 2);
            probe.source.emit(grpcMessage(Generators.letterStrings(2, 6).next()));
            probe.capped.pause();

            probe.time.advanceMillis(DEADLINE_MILLIS - BOUND_MILLIS / 2 - 1);
            boolean quietPastTheBound = probe.failure.get() == null && probe.aborts.get() == 0;
            probe.time.advanceMillis(2 * DEADLINE_MILLIS);
            boolean quietWhilePausedPastTheDeadline = probe.failure.get() == null && probe.aborts.get() == 0;
            probe.capped.resume();

            assertAll("a request stream the upstream held paused past both bounds",
                    () -> assertTrue(quietPastTheBound,
                            "gaps far longer than the bound are not charged to the client while the upstream pauses it"),
                    () -> assertTrue(quietWhilePausedPastTheDeadline,
                            "a deadline passing during the pause ends nothing until the body is read again"),
                    () -> assertInstanceOf(DispatchStage.UpstreamStalledRequest.class, probe.failure.get(),
                            "the read after the deadline ends the body as the upstream's timeout"),
                    () -> assertSame(probe.capped.upstreamStall(), probe.failure.get(),
                            "the stall is the stream's recorded abort, the one the dispatch reports"),
                    () -> assertNull(probe.capped.clientAbort(), "the upstream's stall is never a client abort"),
                    () -> assertEquals(1, probe.aborts.get(), "the upstream request is reset exactly once"),
                    () -> assertTrue(probe.source.paused(), "the read that ended the body never reaches the inbound stream"),
                    () -> assertEquals(0, probe.time.pendingCount(), "nothing is pending once the body was ended"));
        }

        /**
         * The mixed case from the other side: the upstream paused the stream for longer than the bound, then took
         * data again, and the client still held its stream open, within the bound, when the deadline passed. The
         * client held the stream when the deadline passed, so the deadline is the client's, however long the
         * upstream's pause before it was.
         */
        @Test
        @DisplayName("paused by the upstream earlier, read again before the deadline: the deadline is the client's")
        void pausedEarlierThenReadAgainBeforeTheDeadlineIsTheClients() {
            IdleProbe probe = deadlineProbe();
            probe.capped.startMessageClock();
            probe.source.emit(grpcMessage(Generators.letterStrings(2, 6).next()));
            probe.capped.pause();
            probe.time.advanceMillis(2 * BOUND_MILLIS);
            probe.capped.resume();
            probe.time.advanceMillis(DEADLINE_MILLIS - 2 * BOUND_MILLIS - 1);
            boolean openJustBeforeTheDeadline = probe.failure.get() == null && probe.aborts.get() == 0;

            probe.time.advanceMillis(1);

            GatewayException refusal = probe.capped.clientAbort();
            assertAll("a request stream the client held when the deadline passed, after an earlier upstream pause",
                    () -> assertTrue(openJustBeforeTheDeadline,
                            "neither the pause nor the gap after it ended the stream before the deadline"),
                    () -> assertInstanceOf(DispatchStage.RequestBodyDeadlineExceeded.class, refusal,
                            "the client held the stream when the deadline passed"),
                    () -> assertSame(refusal, probe.failure.get(), "the refusal is propagated to the consumer"),
                    () -> assertEquals(1, probe.aborts.get(), "the upstream request is reset exactly once"),
                    () -> assertEquals(0, probe.time.pendingCount(), "nothing is pending once the body was ended"));
        }

        /**
         * The read that resumes the stream checks for a passed deadline before the message clock resumes and again
         * once it has. A deadline timer firing between the two still finds the stream paused by the upstream, and
         * the pause the read ends has lasted the whole deadline, past the stall threshold: the resume decides that
         * as the pause ends, and the second check ends the body as the upstream's.
         */
        @Test
        @DisplayName("a deadline passing inside the read that resumes the stream is the upstream's stall")
        void deadlinePassingInsideTheResumingReadIsTheUpstreamsStall() {
            IdleProbe probe = deadlineProbe();
            probe.capped.startMessageClock();
            probe.source.emit(grpcMessage(Generators.letterStrings(2, 6).next()));
            probe.capped.pause();
            probe.time.advanceMillis(DEADLINE_MILLIS - 1);

            probe.capped.resume(() -> probe.time.advanceMillis(1));

            assertAll("a deadline passing between the read's two passed-deadline checks",
                    () -> assertInstanceOf(DispatchStage.UpstreamStalledRequest.class, probe.failure.get(),
                            "the stream was still paused by the upstream when the deadline passed"),
                    () -> assertSame(probe.capped.upstreamStall(), probe.failure.get()),
                    () -> assertNull(probe.capped.clientAbort(), "the upstream's stall is never a client abort"),
                    () -> assertEquals(1, probe.aborts.get(), "the upstream request is reset exactly once"),
                    () -> assertTrue(probe.source.paused(), "the read that ended the body never reaches the inbound stream"));
        }

        /**
         * The same window from below the stall threshold. The upstream stops taking data {@link #PAUSED_AT_MILLIS}
         * after the clock's start, less than the threshold before the deadline, and the deadline timer fires between
         * the two passed-deadline checks of the read that ends the pause. The resume decides the passed deadline by
         * the length the pause has reached as it ends, and a pause that short is flow control: the second check ends
         * the body as the client's, never as the upstream's stall.
         */
        @Test
        @DisplayName("a deadline passing inside the read that ends a pause shorter than the stall threshold is the client's")
        void deadlinePassingInsideTheReadEndingAShortPauseIsTheClients() {
            IdleProbe probe = deadlineProbe();
            probe.capped.startMessageClock();
            long pausedAt = sendSteadily(probe, PAUSED_AT_MILLIS);
            probe.capped.pause();
            long pauseMillis = DEADLINE_MILLIS - pausedAt;
            probe.time.advanceMillis(pauseMillis - 1);
            boolean quietBeforeTheDeadline = probe.failure.get() == null && probe.aborts.get() == 0;

            probe.capped.resume(() -> probe.time.advanceMillis(1));

            GatewayException refusal = probe.capped.clientAbort();
            assertAll("a deadline passing between the passed-deadline checks of the read ending a short pause",
                    () -> assertTrue(pauseMillis < STALL_MILLIS, "the pause the read ends is shorter than the threshold"),
                    () -> assertTrue(quietBeforeTheDeadline, "nothing ends the body before its deadline"),
                    () -> assertInstanceOf(DispatchStage.RequestBodyDeadlineExceeded.class, refusal,
                            "a pause below the threshold is not the upstream's stall, even inside the resuming read"),
                    () -> assertSame(refusal, probe.failure.get(), "the refusal is propagated to the consumer"),
                    () -> assertNull(probe.capped.upstreamStall(), "nothing is charged to the upstream"),
                    () -> assertEquals(1, probe.aborts.get(), "the upstream request is reset exactly once"),
                    () -> assertTrue(probe.source.paused(), "the read that ended the body never reaches the inbound stream"));
        }

        /**
         * The stall threshold's edge from below. The upstream stops taking data {@link #PAUSED_AT_MILLIS} after the
         * clock's start, the deadline passes during the pause, and the upstream reads again one millisecond short
         * of the threshold after the pause began. A pause that short is the flow control of a client sending faster
         * than a healthy upstream reads, not a stall: the deadline is the client's, exactly as before a deadline
         * could be charged to the upstream at all.
         */
        @Test
        @DisplayName("a pause one millisecond short of the stall threshold when it ends leaves the passed deadline the client's")
        void pauseJustShortOfTheStallThresholdLeavesTheDeadlineTheClients() {
            IdleProbe probe = pausedThroughTheDeadlineAndReadAfter(STALL_MILLIS - 1);

            GatewayException refusal = probe.capped.clientAbort();
            assertAll("a deadline that passed in a pause shorter than the stall threshold",
                    () -> assertInstanceOf(DispatchStage.RequestBodyDeadlineExceeded.class, refusal,
                            "a pause below the threshold is not the upstream's stall"),
                    () -> assertSame(refusal, probe.failure.get(), "the refusal is propagated to the consumer"),
                    () -> assertNull(probe.capped.upstreamStall(), "nothing is charged to the upstream"),
                    () -> assertEquals(1, probe.aborts.get(), "the upstream request is reset exactly once"),
                    () -> assertTrue(probe.source.paused(), "the read that ended the body never reaches the inbound stream"));
        }

        /**
         * The stall threshold's edge reached: the same pause, the upstream reading again exactly the threshold after
         * the pause began. When the deadline passed the pause was still shorter than the threshold; the read that
         * ends it decides, by the length the pause has reached by then, and it has reached the threshold: the
         * upstream's stall.
         */
        @Test
        @DisplayName("a pause that reaches the stall threshold by the read that ends it makes the passed deadline the upstream's stall")
        void pauseReachingTheStallThresholdIsTheUpstreamsStall() {
            IdleProbe probe = pausedThroughTheDeadlineAndReadAfter(STALL_MILLIS);

            assertAll("a deadline that passed in a pause lasting the stall threshold",
                    () -> assertInstanceOf(DispatchStage.UpstreamStalledRequest.class, probe.failure.get(),
                            "a pause of the whole threshold is the upstream's stall"),
                    () -> assertSame(probe.capped.upstreamStall(), probe.failure.get()),
                    () -> assertNull(probe.capped.clientAbort(), "the upstream's stall is never a client abort"),
                    () -> assertEquals(1, probe.aborts.get(), "the upstream request is reset exactly once"));
        }

        /**
         * A timer never fires early, but it may fire late: its delay is rounded up to a whole millisecond, and it
         * runs on the upstream request's event loop, which can be busy while the read that ends the pause runs on
         * the inbound connection's. The upstream stops taking data {@link #PAUSED_AT_MILLIS} after the clock's
         * start and reads again a whole stall threshold later — after the deadline's instant has passed, and before
         * the deadline's timer has run. The pause that read ends spans the deadline and has lasted the threshold,
         * so the read ends the body as the upstream's stall. Left to the late timer, the deadline would be found
         * with the stream being read again and charged to the client, however long the upstream had held it.
         */
        @Test
        @DisplayName("a read ending a stall across the deadline before the deadline's late timer has run is the upstream's stall")
        void readEndingAStallBeforeTheLateDeadlineTimerRanIsTheUpstreamsStall() {
            IdleProbe probe = pausedAcrossTheDeadlineWithItsTimerLate(STALL_MILLIS);

            probe.capped.resume();
            Throwable endedByTheRead = probe.failure.get();
            int pendingAfterTheRead = probe.time.pendingCount();
            probe.time.advanceMillis(0);

            assertAll("a stall the read ends after the deadline's instant, before the deadline's timer ran",
                    () -> assertInstanceOf(DispatchStage.UpstreamStalledRequest.class, endedByTheRead,
                            "the read ends the body as the upstream's stall: the pause spans the deadline and "
                                    + "lasted the threshold"),
                    () -> assertSame(endedByTheRead, probe.capped.upstreamStall(),
                            "the stall is the stream's recorded abort, the one the dispatch reports"),
                    () -> assertNull(probe.capped.clientAbort(), "the upstream's stall is never a client abort"),
                    () -> assertEquals(0, pendingAfterTheRead, "the claim cancelled the late timers"),
                    () -> assertSame(endedByTheRead, probe.failure.get(), "nothing that was overdue changes the end"),
                    () -> assertEquals(1, probe.aborts.get(), "the upstream request is reset exactly once"),
                    () -> assertTrue(probe.source.paused(), "the read that ended the body never reaches the inbound stream"));
        }

        /**
         * Matched control below the threshold: the same late timer, the pause one millisecond short of the stall
         * threshold when the read ends it. That pause is flow control, so the deadline stays the client's, once,
         * whatever the overdue timers find when they run.
         */
        @Test
        @DisplayName("control — a read ending a pause short of the stall threshold before the late timer has run leaves the deadline the client's")
        void readEndingAShortPauseBeforeTheLateDeadlineTimerRanIsTheClients() {
            IdleProbe probe = pausedAcrossTheDeadlineWithItsTimerLate(STALL_MILLIS - 1);

            probe.capped.resume();
            probe.time.advanceMillis(0);

            GatewayException refusal = probe.capped.clientAbort();
            assertAll("a pause below the threshold the read ends after the deadline's instant, before its timer ran",
                    () -> assertInstanceOf(DispatchStage.RequestBodyDeadlineExceeded.class, refusal,
                            "a pause below the threshold is not the upstream's stall"),
                    () -> assertSame(refusal, probe.failure.get(), "the refusal is propagated to the consumer"),
                    () -> assertNull(probe.capped.upstreamStall(), "nothing is charged to the upstream"),
                    () -> assertEquals(1, probe.aborts.get(), "the upstream request is reset exactly once"),
                    () -> assertTrue(probe.source.paused(), "the ended body is not read on"));
        }

        /**
         * A resume ends a pause: the next pause measures its stall from its own start. The upstream holds the
         * stream longer than the threshold, reads again before the deadline, and stops again; the deadline passes
         * in the second pause, which is shorter than the threshold when it ends. The two pauses together are far
         * longer than the threshold, but neither the earlier one nor their sum is a stall at the deadline.
         */
        @Test
        @DisplayName("a resume restarts the stall: a short pause after a long one leaves the passed deadline the client's")
        void resumeRestartsTheStallMeasuredByTheNextPause() {
            IdleProbe probe = deadlineProbe();
            probe.capped.startMessageClock();
            probe.source.emit(grpcMessage(Generators.letterStrings(2, 6).next()));
            probe.capped.pause();
            long secondPauseMillis = STALL_MILLIS - 100;
            long firstPauseMillis = DEADLINE_MILLIS - secondPauseMillis + 100;
            probe.time.advanceMillis(firstPauseMillis);
            probe.capped.resume();
            boolean openAfterTheLongPause = probe.failure.get() == null && probe.aborts.get() == 0;
            probe.capped.pause();
            probe.time.advanceMillis(secondPauseMillis);
            boolean quietWhilePausedPastTheDeadline = probe.failure.get() == null && probe.aborts.get() == 0;

            probe.capped.resume();

            GatewayException refusal = probe.capped.clientAbort();
            assertAll("a deadline that passed in a short pause after a long one",
                    () -> assertTrue(firstPauseMillis > STALL_MILLIS && firstPauseMillis < DEADLINE_MILLIS,
                            "the first pause outlasts the threshold and ends before the deadline"),
                    () -> assertTrue(openAfterTheLongPause, "the long pause ended before the deadline ended nothing"),
                    () -> assertTrue(quietWhilePausedPastTheDeadline,
                            "the deadline passing in the second pause ends nothing until the body is read again"),
                    () -> assertInstanceOf(DispatchStage.RequestBodyDeadlineExceeded.class, refusal,
                            "the second pause alone is measured, and it is shorter than the threshold"),
                    () -> assertNull(probe.capped.upstreamStall(), "nothing is charged to the upstream"),
                    () -> assertEquals(1, probe.aborts.get(), "the upstream request is reset exactly once"));
        }

        /**
         * A pause already in force is not restarted by another pause — the forwarding pipe may pause a paused stream
         * again: the stall is measured from the first, so a pause re-asserted half-way through still reaches the
         * threshold.
         */
        @Test
        @DisplayName("pausing a stream already paused keeps the stall measured from the first pause")
        void pauseWhilePausedKeepsTheStallsStart() {
            IdleProbe probe = deadlineProbe();
            probe.capped.startMessageClock();
            sendSteadily(probe, PAUSED_AT_MILLIS);
            probe.capped.pause();
            probe.time.advanceMillis(STALL_MILLIS / 2);
            probe.capped.pause();
            probe.time.advanceMillis(STALL_MILLIS - STALL_MILLIS / 2);

            probe.capped.resume();

            assertAll("a pause re-asserted while in force",
                    () -> assertInstanceOf(DispatchStage.UpstreamStalledRequest.class, probe.failure.get(),
                            "the stall counts from the first pause and reaches the threshold"),
                    () -> assertNull(probe.capped.clientAbort(), "the upstream's stall is never a client abort"),
                    () -> assertEquals(1, probe.aborts.get(), "the upstream request is reset exactly once"));
        }

        /**
         * Matched control on the whole-body deadline of an HTTP dispatch: the same pause by the upstream past the
         * deadline and the same read afterwards end the body as the client's, exactly as before the pre-response
         * deadline could be charged to an upstream that stalled the stream.
         */
        @Test
        @DisplayName("control — the whole-body deadline of an HTTP dispatch stays the client's after an upstream pause")
        void wholeBodyDeadlinePausedByTheUpstreamStaysTheClients() {
            IdleProbe probe = new IdleProbe(BOUND_MILLIS, DispatchStage.NO_DECLARED_LENGTH,
                    OptionalLong.of(DEADLINE_MILLIS));
            probe.capped.startIdleClock();
            probe.capped.pause();
            probe.time.advanceMillis(2 * DEADLINE_MILLIS);

            probe.capped.resume();

            GatewayException refusal = probe.capped.clientAbort();
            assertAll("a whole-body deadline that passed while the upstream paused the body",
                    () -> assertInstanceOf(DispatchStage.RequestBodyDeadlineExceeded.class, refusal,
                            "the whole-body deadline is the client's whoever held the body"),
                    () -> assertSame(refusal, probe.failure.get()),
                    () -> assertEquals(1, probe.aborts.get(), "the upstream request is reset exactly once"));
        }

        @Test
        @DisplayName("a request stream that ends before the deadline stops it: the end is passed on and nothing is refused")
        void inboundEndStopsTheDeadline() {
            IdleProbe probe = deadlineProbe();
            probe.capped.startMessageClock();
            probe.source.emit(grpcMessage(Generators.letterStrings(2, 6).next()));

            probe.source.end();
            int pendingAfterTheEnd = probe.time.pendingCount();
            probe.time.advanceMillis(2 * DEADLINE_MILLIS);

            assertAll("a request stream half-closed before its deadline",
                    () -> assertEquals(0, pendingAfterTheEnd, "the end cancels the message timer and the deadline"),
                    () -> assertTrue(probe.ended.get(), "the end is passed on"),
                    () -> assertNull(probe.capped.clientAbort()),
                    () -> assertNull(probe.failure.get()),
                    () -> assertEquals(0, probe.aborts.get()));
        }

        /**
         * A timer never fires early, but it may fire late, and the client's end can arrive in between: after the
         * deadline's instant, before the deadline's timer has run. The request stream was still open when its
         * deadline passed, so that end comes too late: the stream is ended at its deadline, as the timer would
         * have ended it on time. It was being read all along, so the deadline is the client's. Left to the end
         * itself, the late timer would be cancelled and the stream passed on as one that had ended in time.
         */
        @ParameterizedTest(name = "the end arrives {0} ms after the deadline's instant")
        @ValueSource(longs = {0L, 1L})
        @DisplayName("an end arriving after the deadline's instant, before the deadline's late timer has run, is ended at the deadline as the client's")
        void inboundEndAfterTheDeadlineInstantBeforeItsLateTimerRanIsTheClientsDeadline(long endAfterTheInstant) {
            IdleProbe probe = deadlineProbe();
            probe.capped.startMessageClock();
            long sent = sendSteadily(probe, DEADLINE_MILLIS - 1);
            probe.time.advanceMillis(DEADLINE_MILLIS - 1 - sent);
            boolean openJustBeforeTheDeadline = probe.failure.get() == null && probe.aborts.get() == 0;
            probe.time.advanceMillisHoldingTimers(1 + endAfterTheInstant);
            int pendingBeforeTheEnd = probe.time.pendingCount();

            probe.source.end();
            GatewayException refusal = probe.capped.clientAbort();
            int pendingAfterTheEnd = probe.time.pendingCount();
            probe.time.advanceMillis(0);

            assertAll("a request stream ended after its deadline's instant, the deadline's timer still pending",
                    () -> assertTrue(openJustBeforeTheDeadline, "nothing ends the stream before its deadline"),
                    () -> assertEquals(2, pendingBeforeTheEnd, "the message timer and the deadline's timer have not run"),
                    () -> assertInstanceOf(DispatchStage.RequestBodyDeadlineExceeded.class, refusal,
                            "the stream was still open at its deadline's instant: the end comes too late"),
                    () -> assertSame(refusal, probe.failure.get(), "the refusal is propagated to the consumer"),
                    () -> assertNull(probe.capped.upstreamStall(),
                            "the stream was being read when the deadline passed: nothing is charged to the upstream"),
                    () -> assertFalse(probe.ended.get(), "the late end is not passed on"),
                    () -> assertEquals(1, probe.aborts.get(), "the upstream request is reset exactly once"),
                    () -> assertEquals(0, probe.source.pauseCount(),
                            "the ended inbound stream is not paused: an ended HTTP/2 request refuses a pause"),
                    () -> assertEquals(0, pendingAfterTheEnd, "the claim cancelled the late timers"));
        }

        /**
         * Matched control on the other side of the instant: the same steady sender, its end one millisecond before
         * the deadline's instant. A request stream that ends before its deadline stops it, and the end is passed on.
         */
        @Test
        @DisplayName("control — an end arriving one millisecond before the deadline's instant is passed on and stops the deadline")
        void inboundEndJustBeforeTheDeadlineInstantIsPassedOn() {
            IdleProbe probe = deadlineProbe();
            probe.capped.startMessageClock();
            long sent = sendSteadily(probe, DEADLINE_MILLIS - 1);
            probe.time.advanceMillis(DEADLINE_MILLIS - 1 - sent);

            probe.source.end();
            int pendingAfterTheEnd = probe.time.pendingCount();
            probe.time.advanceMillis(2 * DEADLINE_MILLIS);

            assertAll("a request stream ended one millisecond before its deadline's instant",
                    () -> assertTrue(probe.ended.get(), "the end is passed on"),
                    () -> assertEquals(0, pendingAfterTheEnd, "the end cancels the message timer and the deadline"),
                    () -> assertNull(probe.capped.clientAbort(), "a stream that ended in time is not refused"),
                    () -> assertNull(probe.failure.get(), "nothing is reported to the consumer"),
                    () -> assertEquals(0, probe.aborts.get(), "the upstream request is not reset"));
        }

        /**
         * Runs the threshold-edge case: the clock starts, a steady sender sends until {@link #PAUSED_AT_MILLIS}, the
         * upstream then stops taking data — the deadline passes during that pause — and reads again
         * {@code pauseMillis} after the pause began.
         *
         * @return the probe, after the read that ends the pause
         */
        private IdleProbe pausedThroughTheDeadlineAndReadAfter(long pauseMillis) {
            IdleProbe probe = deadlineProbe();
            probe.capped.startMessageClock();
            long pausedAt = sendSteadily(probe, PAUSED_AT_MILLIS);
            probe.capped.pause();
            probe.time.advanceMillis(pauseMillis);
            assertTrue(pausedAt < DEADLINE_MILLIS && pausedAt + pauseMillis > DEADLINE_MILLIS,
                    "the deadline passes during the pause");
            assertTrue(probe.failure.get() == null && probe.aborts.get() == 0,
                    "a deadline passing during the pause ends nothing until the body is read again");
            probe.capped.resume();
            return probe;
        }

        /**
         * Runs a pause across the deadline whose timers are late: the clock starts, a steady sender sends until
         * {@link #PAUSED_AT_MILLIS}, the upstream then stops taking data, and {@code pauseMillis} pass without any
         * timer running — so the deadline's instant passes during the pause while its timer is still pending.
         *
         * @return the probe, still paused, before the read that ends the pause
         */
        private IdleProbe pausedAcrossTheDeadlineWithItsTimerLate(long pauseMillis) {
            IdleProbe probe = deadlineProbe();
            probe.capped.startMessageClock();
            long pausedAt = sendSteadily(probe, PAUSED_AT_MILLIS);
            probe.capped.pause();
            probe.time.advanceMillisHoldingTimers(pauseMillis);
            assertTrue(pausedAt < DEADLINE_MILLIS && pausedAt + pauseMillis > DEADLINE_MILLIS,
                    "the deadline's instant passes during the pause");
            assertTrue(probe.failure.get() == null && probe.aborts.get() == 0 && probe.time.pendingCount() > 0,
                    "nothing has ended the body yet: the deadline's timer is overdue and has not run");
            return probe;
        }

        /**
         * Sends a complete message every {@link #STEADY_GAP_MILLIS} while the next one still falls before
         * {@code untilMillis} after the clock's start.
         *
         * @return the milliseconds advanced
         */
        private long sendSteadily(IdleProbe probe, long untilMillis) {
            long advanced = 0;
            while (advanced + STEADY_GAP_MILLIS <= untilMillis) {
                probe.time.advanceMillis(STEADY_GAP_MILLIS);
                advanced += STEADY_GAP_MILLIS;
                probe.source.emit(grpcMessage(Generators.letterStrings(2, 6).next()));
            }
            return advanced;
        }

        /**
         * A probe as the gRPC dispatch builds one: no idle clock, the message clock bounded by
         * {@link #BOUND_MILLIS}, the deadline passing {@link #DEADLINE_MILLIS} after the probe's creation, and the
         * upstream stall threshold {@link #STALL_MILLIS}.
         */
        private IdleProbe deadlineProbe() {
            return new IdleProbe(DispatchStage.NO_BODY_IDLE_TIMEOUT, DispatchStage.NO_DECLARED_LENGTH,
                    OptionalLong.of(DEADLINE_MILLIS), BOUND_MILLIS, STALL_MILLIS);
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
     * dispatch builds one, whose idle clock, request-body deadline and message clock run on a
     * {@link ManualTime}; it records
     * what it forwards, its failure, its end, every abort and every late refusal, and the order the abort
     * action and the late-violation callback ran in.
     */
    private static final class IdleProbe {

        private static final String ABORT = "abort";
        private static final String LATE = "late";

        private final TestReadStream source = new TestReadStream();
        private final DispatchStage.InboundBody inbound = DispatchStage.InboundBody.arm(source);
        private final ManualTime time = new ManualTime();
        private final List<Buffer> forwarded = new ArrayList<>();
        private final AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        private final AtomicInteger aborts = new AtomicInteger();
        private final AtomicBoolean ended = new AtomicBoolean();
        /** The abort action and the late-violation callback, in the order they ran. */
        private final List<String> sequence = new CopyOnWriteArrayList<>();
        private final List<GatewayException> lateViolations = new CopyOnWriteArrayList<>();
        private final DispatchStage.ByteCappedBodyStream capped;

        /** A probe without a request-body deadline. */
        IdleProbe(long timeoutMillis, long declaredLength) {
            this(timeoutMillis, declaredLength, OptionalLong.empty());
        }

        /**
         * @param deadlineMillis how long after the probe's creation its request-body deadline passes —
         *                       {@code 0} for one passing at once — or empty for none
         */
        IdleProbe(long timeoutMillis, long declaredLength, OptionalLong deadlineMillis) {
            this(timeoutMillis, declaredLength, deadlineMillis, DispatchStage.NO_FIRST_MESSAGE_TIMEOUT);
        }

        /**
         * @param deadlineMillis how long after the probe's creation its request-body deadline passes —
         *                       {@code 0} for one passing at once — or empty for none; with an unarmed idle
         *                       clock and an armed message clock it is the pre-response deadline
         * @param messageMillis  the message bound, timed on the same {@link ManualTime}, or
         *                       {@link DispatchStage#NO_FIRST_MESSAGE_TIMEOUT} for an unarmed clock
         */
        IdleProbe(long timeoutMillis, long declaredLength, OptionalLong deadlineMillis, long messageMillis) {
            this(timeoutMillis, declaredLength, deadlineMillis, messageMillis, DispatchStage.NO_UPSTREAM_STALL);
        }

        /**
         * @param deadlineMillis the request-body deadline, as for the constructor without a stall threshold
         * @param messageMillis  the message bound, as for the constructor without a stall threshold
         * @param stallMillis    the upstream stall threshold of a pre-response deadline, timed on the same
         *                       {@link ManualTime}, or {@link DispatchStage#NO_UPSTREAM_STALL} for a probe without one
         */
        IdleProbe(long timeoutMillis, long declaredLength, OptionalLong deadlineMillis, long messageMillis,
                long stallMillis) {
            OptionalLong deadlineNanos = deadlineMillis.isPresent()
                    ? OptionalLong.of(time.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMillis.getAsLong()))
                    : OptionalLong.empty();
            capped = new DispatchStage.ByteCappedBodyStream(inbound, BODY_CAP, declaredLength, () -> {
                        aborts.incrementAndGet();
                        sequence.add(ABORT);
                    }, _ -> {
                        // The forwarded count feeds the retry gate, which these cases do not exercise.
                    }, new DispatchStage.IdleClock(timeoutMillis, time, time::nanoTime), deadlineNanos,
                    new DispatchStage.IdleClock(messageMillis, time, time::nanoTime), stallMillis);
            capped.handler(forwarded::add);
            capped.exceptionHandler(failure::set);
            capped.endHandler(_ -> ended.set(true));
        }

        /**
         * Hands the probe's response over for relay: a deadline refusal from now on reaches
         * {@link #lateViolations}.
         *
         * @return the deadline refusal that came before the hand-over, or {@code null} when there was none
         */
        @Nullable
        GatewayException handOver() {
            return capped.handOver(violation -> {
                sequence.add(LATE);
                lateViolations.add(violation);
            });
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

        /**
         * Moves time forward by {@code millis} without running any task that falls due on the way, as when
         * every timer due in that span fires late. The tasks stay pending and run with the next
         * {@link #advanceMillis(long)} — {@code advanceMillis(0)} runs exactly those that are overdue.
         */
        void advanceMillisHoldingTimers(long millis) {
            nowNanos += TimeUnit.MILLISECONDS.toNanos(millis);
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

    /**
     * The frame observer of {@link DispatchStage.InboundBody}: how a party that has to see the request
     * frames on their way to the dispatch — the gRPC stream-idle bound — does so without wrapping the
     * armed body, which the dispatch has to be handed itself to learn of a client's departure. That the
     * gRPC path hands the dispatch the armed body is pinned by {@code ResponseStageTest} and, through the
     * dispatch itself, by {@code TerminalDispatchGrpcClientDepartureTest}.
     */
    @Nested
    @DisplayName("frame observer of the armed inbound body")
    class InboundFrameObserver {

        private static final String OBSERVED = "observed";

        private final TestReadStream source = new TestReadStream();
        private final DispatchStage.InboundBody inbound = DispatchStage.InboundBody.arm(source);
        /** The observer's runs and the frames the consumer read, in the order they happened. */
        private final List<String> sequence = new ArrayList<>();

        @Test
        @DisplayName("the observer is run for every frame, before the consumer reads it")
        void observerRunsBeforeTheConsumerReadsEachFrame() {
            String first = Generators.letterStrings(2, 6).next();
            String second = Generators.letterStrings(2, 6).next();
            inbound.frameObserver(() -> sequence.add(OBSERVED));
            inbound.handler(frame -> sequence.add(frame.toString()));

            source.emit(Buffer.buffer(first));
            source.emit(Buffer.buffer(second));

            assertEquals(List.of(OBSERVED, first, OBSERVED, second), sequence,
                    "every frame is shown to the observer first, then read by the consumer unchanged");
        }

        /**
         * The observer must not change what the dispatch makes of the body: a byte-capped body subscribed to
         * an observed armed body forwards its frames, and still learns of an inbound failure through it.
         */
        @Test
        @DisplayName("a byte-capped body over an observed armed body forwards every frame and still learns of its failure")
        void observedBodyStillFeedsAndFailsTheByteCappedBody() {
            String frame = Generators.letterStrings(2, 6).next();
            List<Buffer> forwarded = new ArrayList<>();
            AtomicInteger aborts = new AtomicInteger();
            inbound.frameObserver(() -> sequence.add(OBSERVED));
            DispatchStage.ByteCappedBodyStream capped = new DispatchStage.ByteCappedBodyStream(inbound, BODY_CAP,
                    aborts::incrementAndGet);
            capped.handler(forwarded::add);

            source.emit(Buffer.buffer(frame));
            source.fail(new IllegalStateException(Generators.letterStrings(4, 12).next()));

            assertAll("an observed armed body under the dispatch's own decorator",
                    () -> assertEquals(List.of(Buffer.buffer(frame)), forwarded, "the frame crosses to the upstream"),
                    () -> assertEquals(List.of(OBSERVED), sequence, "the observer saw that one frame"),
                    () -> assertEquals(EventType.INBOUND_BODY_ABORTED,
                            assertInstanceOf(GatewayException.class, capped.clientAbort()).getEventType(),
                            "the inbound failure still reaches the byte-capped body"),
                    () -> assertEquals(1, aborts.get(), "and resets the upstream request once"));
        }

        @Test
        @DisplayName("control — without an observer the consumer reads every frame as before")
        void withoutAnObserverTheConsumerReadsEveryFrame() {
            String frame = Generators.letterStrings(2, 6).next();
            inbound.handler(read -> sequence.add(read.toString()));

            source.emit(Buffer.buffer(frame));

            assertEquals(List.of(frame), sequence);
        }

        @Test
        @DisplayName("clearing the consumer's handler stops the observer with it")
        void clearedHandlerStopsTheObserver() {
            inbound.frameObserver(() -> sequence.add(OBSERVED));
            inbound.handler(frame -> sequence.add(frame.toString()));

            inbound.handler(null);
            source.emit(Buffer.buffer(Generators.letterStrings(2, 6).next()));

            assertEquals(List.of(), sequence, "a frame nobody reads is not counted as observed either");
        }

        @Test
        @DisplayName("refuses a null observer")
        void refusesANullObserver() {
            assertThrows(NullPointerException.class, () -> inbound.frameObserver(null));
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

            // Act — the dispatch stops waiting for a second attempt queued behind it; then the first, a
            // bodyless GET that forwarded no body chunk, is answered in full and its connection returns to
            // the pool, and a well-framed drain request follows on the client.
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
            return new DispatchStage(new UpstreamClients(Map.of(), Map.of()), BODY_CAP,
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

        /**
         * A request-body idle expiry is refused a retry because it is a {@link GatewayException}, and for
         * no other reason. On a live dispatch the expiry always follows the subscription of the one-shot
         * body stream, which refuses the retry on its own, so a live case passes whether or not the
         * expiry itself is refused. Here the dispatch's state allows a retry — an idempotent PUT, no
         * body byte sent, the body stream not subscribed — as the upstream failure under the same state
         * shows by being retried; the attempt then fails with the {@link EventType#REQUEST_BODY_TIMEOUT}
         * an expired idle clock recorded, and only the refusal of a gateway rejection keeps it from being
         * re-sent.
         */
        @Test
        @DisplayName("never retries a request-body idle expiry, though the dispatch's state would allow a retry")
        void neverRetriesARequestBodyIdleExpiry() {
            GatewayException expiry = idleExpiry();
            AtomicInteger upstreamFailureAttempts = new AtomicInteger();
            AtomicInteger idleExpiryAttempts = new AtomicInteger();

            GatewayException afterUpstreamFailure = assertThrows(GatewayException.class,
                    () -> dispatchPutWithNoBodySent(() -> {
                        upstreamFailureAttempts.incrementAndGet();
                        throw new ExecutionException("upstream down", new IllegalStateException("upstream down"));
                    }));
            GatewayException afterIdleExpiry = assertThrows(GatewayException.class,
                    () -> dispatchPutWithNoBodySent(() -> {
                        idleExpiryAttempts.incrementAndGet();
                        throw expiry;
                    }));

            assertAll("an idle expiry under a state that allows a retry",
                    () -> assertEquals(3, upstreamFailureAttempts.get(),
                            "the state allows a retry: an upstream failure is retried (1 attempt + 2 retries)"),
                    () -> assertEquals(EventType.UPSTREAM_ERROR, afterUpstreamFailure.getEventType()),
                    () -> assertEquals(1, idleExpiryAttempts.get(), "an idle expiry must never be retried"),
                    () -> assertEquals(EventType.REQUEST_BODY_TIMEOUT, afterIdleExpiry.getEventType(),
                            "the expiry ends the dispatch as the request's"));
        }

        /**
         * Runs {@code attempt} as an idempotent PUT on a retry-enabled route whose body stream was never
         * subscribed and has sent no byte.
         */
        private HttpClientResponse dispatchPutWithNoBodySent(Callable<HttpClientResponse> attempt) {
            return newStage().guardedDispatch(retryGuard(), new StreamAwareRetryGate(true), HttpMethod.PUT,
                    () -> 0L, () -> false, attempt);
        }

        /** @return the {@link EventType#REQUEST_BODY_TIMEOUT} a byte-capped body records when its idle clock expires */
        private GatewayException idleExpiry() {
            long timeoutMillis = 1_000L;
            IdleProbe probe = new IdleProbe(timeoutMillis, DispatchStage.NO_DECLARED_LENGTH);
            probe.capped.startIdleClock();
            probe.time.advanceMillis(timeoutMillis);
            GatewayException expiry = assertInstanceOf(GatewayException.class, probe.capped.clientAbort(),
                    "the idle clock must have expired");
            assertEquals(EventType.REQUEST_BODY_TIMEOUT, expiry.getEventType());
            return expiry;
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
            DispatchStage stage = stageOver(Map.of(UpstreamTarget.of(route), unusedClient()), Map.of());

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

        private DispatchStage stageOver(Map<UpstreamTarget, HttpClient> clients,
                Map<ResilienceShape, Guard> guards) {
            return new DispatchStage(new UpstreamClients(clients, guards), BODY_CAP,
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

            // The breaker rules of the production guard (UpstreamGuards#guardFor), with a window
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
            RouteTable table = new RouteTable(List.of(ConfigFixtures.route("upload", "/upload")
                    .effectiveAllowedMethods(Set.of(HttpMethod.POST))
                    .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstream.actualPort(), ""))
                    .build()));
            RouteAssembler.AssembledRoutes assembled = new RouteAssembler(new ProtocolProcessorRegistry()).assemble(
                    table,
                    _ -> new SecurityPosture(SecurityProfile.STRICT,
                            SecurityConfiguration.builder().build()),
                    _ -> vertx.createHttpClient(),
                    _ -> guard,
                    (_, _) -> {
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
     * holds a pooled upstream connection ends the dispatch as {@link EventType#REQUEST_BODY_TIMEOUT} at
     * once, and a body that forwarded no chunk leaves that connection reusable, because the request head
     * leaves with the first chunk; a body that keeps arriving, however slowly, is never cut off, and
     * neither is a body that goes idle only after the upstream answered: the clock stops when the
     * attempt's send completes.
     * <p>
     * The upstream client holds at most one HTTP/1.1 connection, so a follow-up dispatch on the same
     * client is served only once the idle dispatch, which forwarded no body chunk, has handed that
     * connection back to the pool. Every route's guard leaves the dispatch
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
        /** The production breaker's request-volume threshold (UpstreamGuards#guardFor). */
        private static final int REQUEST_VOLUME_THRESHOLD = 20;
        private static final long PATIENT_TIMEOUT_SECONDS = Awaits.CONNECT_CEILING_SECONDS / 2;
        private static final String ANSWERING_PATH = "/answering";
        /** The path the stub upstream answers at once, before it reads the request body. */
        private static final String EARLY_PATH = "/early";
        /** How long the body of the clock-stop case goes without a byte after the answer: three idle timeouts. */
        private static final long IDLE_GAP_MILLIS = 3 * IDLE_TIMEOUT_MILLIS;
        /** The path whose upstream connection the stub upstream closes as soon as the request head arrives. */
        private static final String FAILING_PATH = "/failing";
        /** The request-body deadline of the failed-attempt case: five idle timeouts. */
        private static final long BODY_DEADLINE_MILLIS = 5 * IDLE_TIMEOUT_MILLIS;
        /** How long after the failed dispatch the failed-attempt case takes its first look at the body. */
        private static final long SETTLE_MILLIS = 200L;
        /** The path of the late-first-byte cases, whose body the stub upstream reads whole and answers. */
        private static final String LATE_FIRST_BYTE_PATH = "/late-first-byte";
        /** How long after the dispatch subscribed the late first byte arrives: three idle timeouts. */
        private static final long LATE_FIRST_BYTE_MILLIS = 3 * IDLE_TIMEOUT_MILLIS;
        private static final String ANSWER = "ok";

        private Vertx vertx;
        private ExecutorService virtualThreadExecutor;
        private HttpServer upstream;
        private HttpClient upstreamClient;
        private final UpstreamFailureMapper failureMapper = new UpstreamFailureMapper(new GatewayEventCounter());
        /**
         * The request body the stub upstream read for the early path: its text once it ended, or the
         * failure it ended with.
         */
        private final CompletableFuture<String> earlyBody = new CompletableFuture<>();
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
            // The stub upstream answers every request once its body has arrived whole, except one to the
            // early path, which it answers at once and then reads to its end, and one to the failing path,
            // whose connection it closes as soon as the head arrives. It is HTTP/1.1 only: without h2c to
            // detect, it reports a connection when it is accepted rather than with its first bytes, and a
            // request whose body never arrives sends none.
            upstream = Awaits.connect(vertx.createHttpServer(new HttpServerOptions().setHttp2ClearTextEnabled(false))
                    .connectionHandler(_ -> acceptedConnections.incrementAndGet())
                    .requestHandler(request -> {
                        receivedPaths.add(request.path());
                        if (FAILING_PATH.equals(request.path())) {
                            request.connection().close();
                            return;
                        }
                        if (EARLY_PATH.equals(request.path())) {
                            request.body().onComplete(read -> earlyBody.complete(
                                    read.succeeded() ? read.result().toString() : "failed: " + read.cause()));
                            request.response().end(ANSWER);
                            return;
                        }
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

        /**
         * The idle clock stops when the attempt's send completes. The upstream answers at once, before it
         * reads the body, so the send completes with the response head while the body is still open; the
         * body then goes {@link #IDLE_GAP_MILLIS} — several idle timeouts — without a byte before it ends.
         * The stage carries no request-body deadline, so nothing but a still-running idle clock could end
         * the body in that gap: had it kept running, it would have reset the upstream request, and the
         * upstream would have seen its body fail instead of end.
         */
        @Test
        @DisplayName("an upstream that answered at once is not ended as REQUEST_BODY_TIMEOUT when the body then idles past the idle timeout")
        void idleClockStopsWhenTheSendCompletes() throws Exception {
            TestReadStream inbound = new TestReadStream();
            String before = Generators.letterStrings(4, 8).next();
            String after = Generators.letterStrings(4, 8).next();

            Future<HttpClientResponse> dispatched = dispatch(routed(IDLE_TIMEOUT_MILLIS, patientGuard(), false),
                    HttpMethod.POST, EARLY_PATH, inbound);
            Awaits.until(inbound::subscribed, "the dispatch to subscribe to the request body",
                    Awaits.CONNECT_CEILING_SECONDS);
            inbound.emit(Buffer.buffer(before));
            String answer = answerOf(dispatched);
            vertx.setTimer(IDLE_GAP_MILLIS, _ -> {
                inbound.emit(Buffer.buffer(after));
                inbound.end();
            });
            String received = Awaits.connect(earlyBody, "the upstream to read the request body to its end");

            assertAll("a body idling past the idle timeout after the upstream answered",
                    () -> assertEquals("200 " + ANSWER, answer, "the upstream's answer is handed back"),
                    () -> assertEquals(before + after, received,
                            "the body reaches the upstream whole: the idle clock stopped when the send completed"),
                    () -> assertEquals(List.of(EARLY_PATH), receivedPaths));
        }

        /**
         * An HTTP dispatch is built with {@link DispatchStage#NO_FIRST_MESSAGE_TIMEOUT}, so it arms neither the
         * message clock nor the pre-response deadline: a body whose first byte arrives
         * {@link #LATE_FIRST_BYTE_MILLIS} after the dispatch subscribed to it — three times the bound of the
         * matched control below — is forwarded whole. The stage arms no idle clock either, so nothing else could
         * end it.
         */
        @Test
        @DisplayName("an HTTP dispatch never arms the message clock: a late first byte is forwarded and answered")
        void httpDispatchNeverArmsTheMessageClock() throws Exception {
            TestReadStream inbound = new TestReadStream();
            String chunk = Generators.letterStrings(4, 8).next();

            Future<HttpClientResponse> dispatched = dispatch(routed(DispatchStage.NO_BODY_IDLE_TIMEOUT, patientGuard(),
                    false), HttpMethod.POST, LATE_FIRST_BYTE_PATH, inbound);
            emitLate(inbound, Buffer.buffer(chunk));
            String answer = answerOf(dispatched);

            assertAll("an HTTP dispatch whose first body byte arrived late",
                    () -> assertEquals("200 " + ANSWER, answer, "the late first byte does not end the dispatch"),
                    () -> assertEquals(Integer.valueOf(chunk.length()), receivedBodyBytes.get(LATE_FIRST_BYTE_PATH),
                            "the upstream receives the whole body"));
        }

        /**
         * Matched control of {@link #httpDispatchNeverArmsTheMessageClock()}: a complete first message arriving
         * as late under a stage bounding the wait for each complete message at {@link #IDLE_TIMEOUT_MILLIS} ends
         * the dispatch at the bound as the client's abort. The request head leaves with the first chunk, so the
         * reset leaves the pool's only connection reusable for the next dispatch.
         */
        @Test
        @DisplayName("control — a late complete first message under a message bound ends the dispatch as a RequestMessageTimeout")
        void firstMessageBoundEndsALateFirstMessage() throws Exception {
            TestReadStream inbound = new TestReadStream();
            Routed bounded = Routed.withMessageBound("message-bound",
                    new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstream.actualPort(), ""), upstreamClient,
                    patientGuard(), IDLE_TIMEOUT_MILLIS, failureMapper);

            Future<HttpClientResponse> dispatched = dispatch(bounded, HttpMethod.POST, LATE_FIRST_BYTE_PATH, inbound);
            emitLate(inbound, grpcMessage(Generators.letterStrings(4, 8).next()));
            GatewayException rejection = rejectionOf(dispatched);
            String next = answerOf(dispatch(routed(DispatchStage.NO_BODY_IDLE_TIMEOUT, patientGuard(), false),
                    HttpMethod.GET, ANSWERING_PATH, new TestReadStream()));

            assertAll("a bounded dispatch whose complete first message arrived late",
                    () -> assertInstanceOf(DispatchStage.RequestMessageTimeout.class, rejection,
                            () -> "the client's abort, not an upstream failure: " + rejection),
                    () -> assertEquals(EventType.INBOUND_BODY_ABORTED, rejection.getEventType()),
                    () -> assertEquals("200 " + ANSWER, next, "the pool's only connection is free for the next dispatch"),
                    () -> assertEquals(List.of(ANSWERING_PATH), receivedPaths,
                            "the bounded request never sent its head; only the next dispatch reached the upstream"));
        }

        /** Emits {@code chunk} and ends the body {@link #LATE_FIRST_BYTE_MILLIS} after the dispatch subscribed to it. */
        private void emitLate(TestReadStream inbound, Buffer chunk) throws Exception {
            Awaits.until(inbound::subscribed, "the dispatch to subscribe to the request body",
                    Awaits.CONNECT_CEILING_SECONDS);
            vertx.setTimer(LATE_FIRST_BYTE_MILLIS, _ -> {
                inbound.emit(chunk);
                inbound.end();
            });
        }

        /**
         * An attempt that ends without handing its response back stops its body's request-body deadline.
         * The upstream closes the connection as soon as the request head arrives, so the dispatch fails as
         * an upstream failure long before the deadline. Once the deadline has passed, nothing has touched
         * the body of that failed attempt: a deadline still running would have paused the inbound stream
         * and reset the upstream request a second time when it passed.
         */
        @Test
        @DisplayName("an attempt that failed upstream stops its request-body deadline: the body is left alone when the deadline passes")
        void failedAttemptStopsTheRequestBodyDeadline() throws Exception {
            TestReadStream inbound = new TestReadStream();
            List<GatewayException> lateViolations = new CopyOnWriteArrayList<>();
            Routed routed = Routed.of("body-deadline",
                    new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstream.actualPort(), ""), false, upstreamClient,
                    patientGuard(), IDLE_TIMEOUT_MILLIS, BODY_DEADLINE_MILLIS, failureMapper);

            Future<HttpClientResponse> dispatched = virtualThreadExecutor.submit(() -> routed.dispatch(HttpMethod.POST,
                    FAILING_PATH, Map.of(), inbound,
                    DispatchStage.BodyFraming.of(HttpMethod.POST, DispatchStage.NO_DECLARED_LENGTH, lateViolations::add)));
            Awaits.until(inbound::subscribed, "the dispatch to subscribe to the request body",
                    Awaits.CONNECT_CEILING_SECONDS);
            inbound.emit(Buffer.buffer(Generators.letterStrings(4, 8).next()));
            GatewayException rejection = rejectionOf(dispatched);
            int pausesOnceSettled = pauseCountAfter(inbound, SETTLE_MILLIS);
            int pausesPastTheDeadline = pauseCountAfter(inbound, BODY_DEADLINE_MILLIS);

            assertAll("the body of an attempt that failed upstream",
                    () -> assertEquals(EventType.UPSTREAM_ERROR, rejection.getEventType()),
                    () -> assertEquals(pausesOnceSettled, pausesPastTheDeadline,
                            "no request-body deadline pauses the body once its attempt has ended"),
                    () -> assertEquals(List.of(), lateViolations, "nothing reaches the late-violation callback"));
        }

        /** @return how often {@code inbound} has been paused, read {@code delayMillis} from now */
        private int pauseCountAfter(TestReadStream inbound, long delayMillis) throws Exception {
            CompletableFuture<Integer> count = new CompletableFuture<>();
            vertx.setTimer(delayMillis, _ -> count.complete(inbound.pauseCount()));
            return Awaits.connect(count, "the body to be looked at again");
        }

        /** A guard whose timeout leaves the dispatch far more time than any idle timeout of these cases. */
        private Guard patientGuard() {
            return Guard.create()
                    .withTimeout().duration(PATIENT_TIMEOUT_SECONDS, ChronoUnit.SECONDS).done()
                    .build();
        }

        /** The breaker rules of the production guard (UpstreamGuards#guardFor) inside a patient timeout. */
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
                    new PathConfinement(), fetcher, 1024L, Map.of(), false);

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
     * deferred onto the client connection's event loop exactly as {@code TerminalDispatch} defers it,
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
            ResponseStage responseStage = new ResponseStage(GatewayCookies.of(ConfigFixtures.gateway().build()));
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
                    responseStage.relay(response, request.response(), false, null, CookiesConfig.NONE, false,
                            Map.of(), Map.of(), Supplier::get)
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
        /** How often {@link #pause()} was called. */
        private final AtomicInteger pauseCalls = new AtomicInteger();
        private volatile @Nullable Buffer onResume;

        /** @return {@code true} while the last call that changed the flow was a pause */
        boolean paused() {
            return paused;
        }

        /** @return how often the stream has been paused so far */
        int pauseCount() {
            return pauseCalls.get();
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
            pauseCalls.incrementAndGet();
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
