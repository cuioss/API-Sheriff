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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;


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
import de.cuioss.sheriff.gateway.config.model.ResolvedRoute;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayEventCounter;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.routing.ProtocolProcessorRegistry;
import de.cuioss.sheriff.gateway.routing.RouteRuntime;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import io.smallrye.faulttolerance.api.CircuitBreakerState;
import io.smallrye.faulttolerance.api.Guard;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServer;
import io.vertx.core.streams.ReadStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("DispatchStage — stage 6 streamed upstream dispatch")
class DispatchStageTest {

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
    }

    @Nested
    @DisplayName("stream-aware retry gating on the guarded dispatch path")
    class RetryGating {

        private DispatchStage newStage() {
            return new DispatchStage(1024L, new UpstreamFailureMapper(new GatewayEventCounter()));
        }

        /** A retry-enabled guard (1 + 2 retries), aborting on {@link GatewayException} like production. */
        private Guard retryGuard() {
            return Guard.create()
                    .withRetry().maxRetries(2).delay(0, ChronoUnit.MILLIS)
                    .abortOn(GatewayException.class).done()
                    .build();
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
        @DisplayName("never retries a non-idempotent POST — the retry re-entry is aborted")
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

    @Nested
    @DisplayName("circuit-breaker attribution over a live upstream")
    class BreakerAttribution {

        /** The breaker's rolling window: this many failed calls in a row open it. */
        private static final int REQUEST_VOLUME_THRESHOLD = 4;
        private static final String ACCEPTING_PATH = "/accepting";
        private static final String FAILING_PATH = "/failing";

        private Vertx vertx;
        private ExecutorService virtualThreadExecutor;
        private HttpServer upstream;
        private RouteRuntime route;
        private DispatchStage stage;
        /** Every request the stub upstream received a head for. */
        private final AtomicInteger upstreamStarted = new AtomicInteger();
        /** Every state the route's breaker moved to, in order. */
        private final List<CircuitBreakerState> breakerTransitions = new CopyOnWriteArrayList<>();

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
            // The stub upstream answers a request once its body has arrived whole, and drops the
            // connection of any request to the failing path as soon as its head arrives.
            upstream = Awaits.connect(vertx.createHttpServer().requestHandler(request -> {
                upstreamStarted.incrementAndGet();
                if (FAILING_PATH.equals(request.path())) {
                    request.connection().close();
                    return;
                }
                request.body().onSuccess(body -> request.response().end("ok"));
            }).listen(0, LoopbackHost.ADDRESS), "the stub upstream to start listening");

            // The breaker rules of the production guard (GatewayEdgeRoute#guardFor), with a window
            // small enough to fill within one test.
            Guard guard = Guard.create()
                    .withCircuitBreaker()
                    .requestVolumeThreshold(REQUEST_VOLUME_THRESHOLD)
                    .failureRatio(0.5)
                    .delay(1, ChronoUnit.MINUTES)
                    .skipOn(GatewayException.class)
                    .onStateChange(breakerTransitions::add)
                    .done()
                    .build();
            RouteTable table = new RouteTable(List.of(ResolvedRoute.builder()
                    .id("upload")
                    .protocol(Protocol.HTTP)
                    .match(MatchConfig.builder().pathPrefix("/upload").build())
                    .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                    .effectiveAllowedMethods(List.of(HttpMethod.POST))
                    .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstream.actualPort(), ""))
                    .build()));
            route = new RouteRuntimeAssembler(new ProtocolProcessorRegistry()).assemble(table,
                    _ -> new RouteRuntimeAssembler.SecurityPosture(SecurityProfile.STRICT,
                            SecurityConfiguration.builder().build()),
                    _ -> vertx.createHttpClient(),
                    _ -> guard,
                    _ -> {
                        throw new UnsupportedOperationException("no asset route in this test");
                    }).getFirst();
            stage = new DispatchStage(1024L, new UpstreamFailureMapper(new GatewayEventCounter()));
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

        /** Runs one dispatch on a virtual thread, as the edge does, so the test thread can drive its body. */
        private Future<HttpClientResponse> dispatch(String path, ReadStream<Buffer> inbound) {
            return virtualThreadExecutor.submit(() -> stage.dispatch(route, HttpMethod.POST, path, Map.of(), inbound));
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

        /** Awaits a dispatch that must fail and returns the {@link GatewayException} it raised. */
        private GatewayException rejectionOf(Future<HttpClientResponse> dispatched) {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> Awaits.connect(dispatched, "the dispatch to be rejected"));
            return assertInstanceOf(GatewayException.class, failure.getCause());
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
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            resumed = true;
            return this;
        }

        @Override
        public ReadStream<Buffer> fetch(long amount) {
            return this;
        }

        @Override
        public ReadStream<Buffer> endHandler(@Nullable Handler<Void> endHandler) {
            this.endHandler = endHandler;
            return this;
        }
    }
}
