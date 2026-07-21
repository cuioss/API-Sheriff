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
package de.cuioss.sheriff.api.edge;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;


import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.sheriff.api.config.model.AuthConfig;
import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.config.model.MatchConfig;
import de.cuioss.sheriff.api.config.model.Protocol;
import de.cuioss.sheriff.api.config.model.ResolvedRoute;
import de.cuioss.sheriff.api.config.model.ResolvedUpstream;
import de.cuioss.sheriff.api.config.model.RouteTable;
import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayEventCounter;
import de.cuioss.sheriff.api.events.GatewayException;
import de.cuioss.sheriff.api.routing.ProtocolProcessorRegistry;
import de.cuioss.sheriff.api.routing.RouteRuntime;

import io.smallrye.faulttolerance.api.Guard;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract of the {@code protocol: grpc} dispatch path (deliverable 6): the forced-HTTP/2 upstream
 * client wiring (a gRPC route holds a client distinct from an HTTP/1.1 route to the same host:port),
 * the h2-negotiation-failure → gRPC {@code UNAVAILABLE} mapping, the response-trailer relay carrying
 * {@code grpc-status} / {@code grpc-message} to the client, and the shared inbound-transport GW-08
 * abuse bounds that hold on the gRPC path without a per-protocol relaxation.
 */
@DisplayName("GrpcDispatchStage — forced-h2 wiring, UNAVAILABLE mapping, trailer relay, GW-08 bounds")
class GrpcDispatchStageTest {

    @Nested
    @DisplayName("constructor contract")
    class ConstructorContract {

        @Test
        @DisplayName("rejects a null failure mapper")
        void rejectsNullFailureMapper() {
            assertThrows(NullPointerException.class, () -> new GrpcDispatchStage(1024L, null),
                    "the gRPC dispatch stage requires a non-null failure mapper");
        }

        @Test
        @DisplayName("builds with a body cap and a failure mapper")
        void buildsWithFailureMapper() {
            assertDoesNotThrow(() -> new GrpcDispatchStage(1024L, new UpstreamFailureMapper(new GatewayEventCounter())),
                    "a body cap plus a failure mapper is a valid gRPC dispatch stage");
        }
    }

    @Nested
    @DisplayName("forced-h2 upstream client wiring")
    class ForcedH2ClientWiring {

        private Vertx vertx;
        private RouteRuntimeAssembler assembler;
        private final List<RouteRuntimeAssembler.UpstreamTarget> capturedTargets = new ArrayList<>();
        private RouteRuntimeAssembler.UpstreamClientFactory clientFactory;
        private RouteRuntimeAssembler.SecurityConfigurationFactory securityConfigFactory;
        private RouteRuntimeAssembler.ResilienceGuardFactory guardFactory;
        private RouteRuntimeAssembler.AssetSourceFactory assetSourceFactory;

        @BeforeEach
        void setUp() {
            vertx = Vertx.vertx();
            assembler = new RouteRuntimeAssembler(new ProtocolProcessorRegistry());
            clientFactory = target -> {
                capturedTargets.add(target);
                return vertx.createHttpClient();
            };
            securityConfigFactory = filter -> SecurityConfiguration.builder().build();
            guardFactory = shape -> new StoredOnlyGuard();
            assetSourceFactory = asset -> {
                throw new UnsupportedOperationException("no asset route in this test");
            };
        }

        @AfterEach
        void tearDown() {
            vertx.close();
        }

        @Test
        @DisplayName("gives a gRPC route a forced-h2 client distinct from an HTTP/1.1 route to the same host:port")
        void splitsForcedH2ClientFromHttp1Client() {
            // Arrange — a gRPC route and an HTTP route to the SAME upstream host:port
            RouteTable table = new RouteTable(List.of(
                    route("g", Protocol.GRPC, upstream("svc.internal")),
                    route("h", Protocol.HTTP, upstream("svc.internal"))));

            // Act
            List<RouteRuntime> runtimes = assembler.assemble(table, securityConfigFactory, clientFactory,
                    guardFactory, assetSourceFactory);

            // Assert — the forced-h2 flag splits the client-sharing tuple, so the two routes hold
            // distinct clients even though the host:port is identical.
            assertEquals(2, capturedTargets.size(), "two distinct upstream tuples build two clients");
            assertTrue(capturedTargets.get(0).forcedHttp2(), "the gRPC route's upstream target is forced to HTTP/2");
            assertFalse(capturedTargets.get(1).forcedHttp2(), "the HTTP route's upstream target is not forced to HTTP/2");
            assertEquals(capturedTargets.get(0).host(), capturedTargets.get(1).host(),
                    "both targets address the same host");
            assertEquals(capturedTargets.get(0).port(), capturedTargets.get(1).port(),
                    "both targets address the same port");
            assertNotSame(runtimes.get(0).getHttpClient().orElseThrow(),
                    runtimes.get(1).getHttpClient().orElseThrow(),
                    "a gRPC route gets a forced-h2 client distinct from the HTTP/1.1 client to the same host:port");
        }

        @Test
        @DisplayName("shares one forced-h2 client across two gRPC routes to the same host:port")
        void sharesForcedH2ClientAcrossGrpcRoutes() {
            // Arrange — two gRPC routes to the same upstream
            RouteTable table = new RouteTable(List.of(
                    route("g1", Protocol.GRPC, upstream("svc.internal")),
                    route("g2", Protocol.GRPC, upstream("svc.internal"))));

            // Act
            List<RouteRuntime> runtimes = assembler.assemble(table, securityConfigFactory, clientFactory,
                    guardFactory, assetSourceFactory);

            // Assert — one forced-h2 tuple, one shared client
            assertEquals(1, capturedTargets.size(), "two gRPC routes to one host:port share a single forced-h2 tuple");
            assertTrue(capturedTargets.get(0).forcedHttp2(), "the shared tuple is forced to HTTP/2");
            assertSame(runtimes.get(0).getHttpClient().orElseThrow(),
                    runtimes.get(1).getHttpClient().orElseThrow(),
                    "gRPC routes sharing a host:port reuse one forced-h2 client");
        }
    }

    @Nested
    @DisplayName("h2-negotiation-failure mapping to gRPC status")
    class H2FailureMapping {

        private final UpstreamFailureMapper failureMapper = new UpstreamFailureMapper(new GatewayEventCounter());
        private final GrpcStatusMapper grpcStatusMapper = new GrpcStatusMapper();

        @Test
        @DisplayName("maps an h2-negotiation dial failure through UPSTREAM_ERROR to gRPC UNAVAILABLE")
        void mapsH2DialFailureToUnavailable() {
            // Arrange — a forced-h2 dial that could not establish h2 surfaces as a connection failure
            Throwable h2DialFailure = new java.net.ConnectException("failed to negotiate h2 with upstream");

            // Act — the failure classifies as UPSTREAM_ERROR (502), which renders as gRPC UNAVAILABLE
            EventType classified = failureMapper.classify(h2DialFailure);

            // Assert
            assertEquals(EventType.UPSTREAM_ERROR, classified,
                    "an h2-negotiation dial failure is an upstream connection failure (502)");
            assertEquals(GrpcStatusMapper.UNAVAILABLE, grpcStatusMapper.toGrpcStatus(classified),
                    "a 502 upstream failure renders as gRPC UNAVAILABLE (14) on the gRPC path");
        }

        @Test
        @DisplayName("maps an upstream timeout through UPSTREAM_TIMEOUT to gRPC DEADLINE_EXCEEDED")
        void mapsTimeoutToDeadlineExceeded() {
            // Arrange
            Throwable timeout = new TimeoutException("upstream deadline elapsed");

            // Act
            EventType classified = failureMapper.classify(timeout);

            // Assert — the timeout arm is distinct from the generic h2 dial failure
            assertEquals(EventType.UPSTREAM_TIMEOUT, classified, "a timeout classifies as UPSTREAM_TIMEOUT (504)");
            assertEquals(GrpcStatusMapper.DEADLINE_EXCEEDED, grpcStatusMapper.toGrpcStatus(classified),
                    "a 504 upstream timeout renders as gRPC DEADLINE_EXCEEDED (4)");
        }
    }

    @Nested
    @DisplayName("response-trailer relay over a live Vert.x server")
    class TrailerRelay {

        private Vertx vertx;
        private HttpClient client;
        private HttpServer upstream;
        private HttpServer front;

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            client = vertx.createHttpClient();

            // Stub upstream: a chunked opaque gRPC frame followed by grpc-status / grpc-message trailers.
            upstream = vertx.createHttpServer().requestHandler(req -> {
                HttpServerResponse response = req.response();
                response.setChunked(true);
                response.write(Buffer.buffer("opaque-grpc-frame"));
                response.putTrailer("grpc-status", "0");
                response.putTrailer("grpc-message", "ok");
                response.end();
            }).listen(0).toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
            int upstreamPort = upstream.actualPort();

            // Front server: relays the upstream response WITH its trailers exactly as the gRPC dispatch
            // path does (ResponseStage#relayWithTrailers).
            ResponseStage responseStage = new ResponseStage();
            front = vertx.createHttpServer().requestHandler(clientReq -> client
                    .request(io.vertx.core.http.HttpMethod.POST, upstreamPort, "localhost", "/svc.Service/Method")
                    .compose(upReq -> upReq.send())
                    .onSuccess(upResp -> responseStage
                            .relayWithTrailers(upResp, clientReq.response(), false, Map.of())
                            .onFailure(failure -> clientReq.response().setStatusCode(502).end()))
                    .onFailure(failure -> clientReq.response().setStatusCode(502).end()))
                    .listen(0).toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        }

        @AfterEach
        void tearDown() throws Exception {
            front.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            upstream.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("relays the upstream grpc-status / grpc-message trailers to the client")
        void relaysUpstreamTrailers() throws Exception {
            // Arrange
            int frontPort = front.actualPort();
            AtomicReference<Buffer> body = new AtomicReference<>();

            // Act — POST the front server and read the full response including its trailers
            MultiMap trailers = client
                    .request(io.vertx.core.http.HttpMethod.POST, frontPort, "localhost", "/svc.Service/Method")
                    .compose(req -> req.send())
                    .compose(resp -> resp.body().map(buffer -> {
                        body.set(buffer);
                        return resp.trailers();
                    }))
                    .toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);

            // Assert — the opaque frame is streamed through and the gRPC status trailers reach the client
            assertEquals("opaque-grpc-frame", body.get().toString(),
                    "the opaque gRPC frame is streamed through untouched");
            assertEquals("0", trailers.get("grpc-status"),
                    "the upstream grpc-status trailer is relayed to the client");
            assertEquals("ok", trailers.get("grpc-message"),
                    "the upstream grpc-message trailer is relayed to the client");
        }
    }

    @Nested
    @DisplayName("legitimate multi-frame streaming under the shared body byte cap (GW-08 on the gRPC path)")
    class SharedBodyCap {

        @Test
        @DisplayName("streams legitimate multi-frame gRPC traffic under the cap without misfiring")
        void streamsMultiFrameUnderCapWithoutMisfire() {
            // Arrange — the opaque length-prefixed frames the gRPC dispatch streams ride the SAME
            // byte-capped body stream as the HTTP path (DispatchStage.ByteCappedBodyStream); a
            // legitimate multi-frame body under the cap must never be misfired as abuse.
            TestReadStream source = new TestReadStream();
            List<Buffer> forwarded = new ArrayList<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean aborted = new AtomicBoolean();
            DispatchStage.ByteCappedBodyStream capped =
                    new DispatchStage.ByteCappedBodyStream(source, 20L, () -> aborted.set(true));
            capped.handler(forwarded::add);
            capped.exceptionHandler(failure::set);

            // Act — three 5-byte opaque frames = 15 bytes, all under the 20-byte cap
            source.emit(Buffer.buffer("frame"));
            source.emit(Buffer.buffer("frame"));
            source.emit(Buffer.buffer("frame"));

            // Assert — every in-cap frame streams through and nothing is aborted
            assertEquals(3, forwarded.size(), "legitimate multi-frame gRPC traffic under the cap streams through");
            assertNull(failure.get(), "no failure is raised while under the cap");
            assertFalse(aborted.get(), "legitimate multi-frame traffic is not misfired as abuse");
        }

        @Test
        @DisplayName("aborts the gRPC dispatch with PARAMETER_LIMIT_EXCEEDED when the body cap is breached")
        void abortsOnBodyCapBreach() {
            // Arrange — the shared body-abuse bound also applies to the opaque gRPC frame stream
            TestReadStream source = new TestReadStream();
            List<Buffer> forwarded = new ArrayList<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean aborted = new AtomicBoolean();
            DispatchStage.ByteCappedBodyStream capped =
                    new DispatchStage.ByteCappedBodyStream(source, 8L, () -> aborted.set(true));
            capped.handler(forwarded::add);
            capped.exceptionHandler(failure::set);

            // Act — 5 bytes (ok) then 5 more crossing the 8-byte cap
            source.emit(Buffer.buffer("frame"));
            source.emit(Buffer.buffer("flood"));

            // Assert — the breaching frame is dropped, the dispatch is aborted, and a 400 is raised
            assertEquals(1, forwarded.size(), "the breaching frame must never cross to the upstream");
            assertTrue(aborted.get(), "a body-cap breach aborts the in-flight gRPC dispatch");
            GatewayException raised = assertInstanceOf(GatewayException.class, failure.get(),
                    "the breach raises a GatewayException");
            assertEquals(EventType.PARAMETER_LIMIT_EXCEEDED, raised.getEventType(),
                    "the shared body-abuse bound raises PARAMETER_LIMIT_EXCEEDED on the gRPC path");
        }
    }

    private static ResolvedRoute route(String id, Protocol protocol, ResolvedUpstream upstream) {
        return ResolvedRoute.builder()
                .id(id)
                .protocol(protocol)
                .match(MatchConfig.builder().pathPrefix("/" + id).build())
                .effectiveAuth(AuthConfig.builder().require("none").build())
                .effectiveAllowedMethods(List.of(HttpMethod.POST))
                .upstream(Optional.of(upstream))
                .build();
    }

    private static ResolvedUpstream upstream(String host) {
        return new ResolvedUpstream("https", host, 443, "");
    }

    /**
     * A minimal {@link io.vertx.core.streams.ReadStream} fake mirroring {@code DispatchStageTest}:
     * captures the handler the byte-cap decorator installs and lets a test push buffers synchronously.
     */
    private static final class TestReadStream implements io.vertx.core.streams.ReadStream<Buffer> {

        private io.vertx.core.Handler<Buffer> handler;

        void emit(Buffer buffer) {
            if (handler != null) {
                handler.handle(buffer);
            }
        }

        @Override
        public io.vertx.core.streams.ReadStream<Buffer> handler(io.vertx.core.Handler<Buffer> handler) {
            this.handler = handler;
            return this;
        }

        @Override
        public io.vertx.core.streams.ReadStream<Buffer> exceptionHandler(io.vertx.core.Handler<Throwable> handler) {
            return this;
        }

        @Override
        public io.vertx.core.streams.ReadStream<Buffer> pause() {
            return this;
        }

        @Override
        public io.vertx.core.streams.ReadStream<Buffer> resume() {
            return this;
        }

        @Override
        public io.vertx.core.streams.ReadStream<Buffer> fetch(long amount) {
            return this;
        }

        @Override
        public io.vertx.core.streams.ReadStream<Buffer> endHandler(io.vertx.core.Handler<Void> endHandler) {
            return this;
        }
    }

    /**
     * A {@link Guard} test double that is only ever stored on a {@link RouteRuntime} and never invoked
     * during assembly, so its guard methods reject execution.
     */
    private static final class StoredOnlyGuard implements Guard {

        @Override
        public <T> T call(Callable<T> action, Class<T> asType) {
            throw new UnsupportedOperationException("stored-only test guard");
        }

        @Override
        public <T> T call(Callable<T> action, TypeLiteral<T> asType) {
            throw new UnsupportedOperationException("stored-only test guard");
        }

        @Override
        public <T> T get(Supplier<T> action, Class<T> asType) {
            throw new UnsupportedOperationException("stored-only test guard");
        }

        @Override
        public <T> T get(Supplier<T> action, TypeLiteral<T> asType) {
            throw new UnsupportedOperationException("stored-only test guard");
        }
    }
}
