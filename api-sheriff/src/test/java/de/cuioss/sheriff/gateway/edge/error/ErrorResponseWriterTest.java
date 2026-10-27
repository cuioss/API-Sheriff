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
package de.cuioss.sheriff.gateway.edge.error;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongUnaryOperator;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.cuioss.http.forwarded.ForwardedHeaderResolver;
import de.cuioss.http.forwarded.ForwardedResolverConfig;
import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.sheriff.gateway.bff.runtime.GatewayJson;
import de.cuioss.sheriff.gateway.bff.runtime.SessionIdentity;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EnumerationDetectionConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.PortalConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.detection.SourceKey;
import de.cuioss.sheriff.gateway.detection.SourceKeyResolver;
import de.cuioss.sheriff.gateway.edge.admission.SourceGate;
import de.cuioss.sheriff.gateway.edge.end.ResponseDelay;
import de.cuioss.sheriff.gateway.edge.relay.GrpcStatusMapper;
import de.cuioss.sheriff.gateway.edge.upstream.UpstreamGuards;
import de.cuioss.sheriff.gateway.events.EventCategory;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayEventCounter;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.forward.TcpPeerGate;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.portal.ErrorPageClassifier;
import de.cuioss.sheriff.gateway.portal.PortalCatalog;
import de.cuioss.sheriff.gateway.portal.PortalEndpoint;
import de.cuioss.sheriff.gateway.portal.PortalRenderer;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The rejection renderer. The {@code Retry-After} header of an open circuit's {@code 503}:
 * {@link ErrorResponseWriter#seedRetryAfter} seeds it onto the response-header map every
 * gateway-authored answer is written from, for that one event and no other, with the window the breaker
 * itself applies ({@link UpstreamGuards#BREAKER_RESET_SECONDS}). And the trust-boundary branch: a
 * pre-trust rejection on a selected route is rendered as the unrouted {@code 404} — its response headers
 * restored to the set taken at route selection, its stage cookies dropped, a gRPC route answered as an
 * HTTP {@code 404}, the HTML page negotiated under the {@code NO_ROUTE_MATCHED} classification — while it
 * is still reported under its true event. Its release is computed on the event loop from the request's
 * admission reading ({@link ResponseDelay#ADMITTED_AT_KEY}) under {@link UniformNotFound#release}, for the
 * problem document and the HTML page alike, and a release past the first step is metered. Two admission
 * readings guard the order of the release arguments: one 1.5 floors old fails a release computed from
 * the admission reading twice, and one a floor ahead of the writer's clock fails swapped arguments and a
 * release computed from the writer's reading twice. And an unexpected internal failure
 * ({@link ErrorResponseWriter#renderUnexpected}): before the trust boundary on a selected route it is the
 * floored unrouted {@code 404}, counted under {@link EventType#PRE_TRUST_INTERNAL_FAILURE}; everywhere
 * else the opaque {@code 500}.
 */
@EnableGeneratorController
@DisplayName("ErrorResponseWriter — Retry-After of an open circuit and the trust-boundary branch")
@Tag("isolated-fork")
class ErrorResponseWriterTest {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String HSTS = "Strict-Transport-Security";
    private static final String GLOBAL_HSTS = "max-age=31536000";
    private static final String FRAME_OPTIONS = "X-Frame-Options";
    private static final String WWW_AUTHENTICATE = "WWW-Authenticate";
    private static final String ALLOW = "Allow";
    private static final String VARY = "Vary";
    private static final String SET_COOKIE = "Set-Cookie";
    private static final String CACHE_CONTROL = "Cache-Control";
    /** The floor, which is also the width of one release step. */
    private static final long FLOOR_NANOS = UniformNotFound.RESPONSE_FLOOR.toNanos();
    /** The exclusive upper bound of the jitter on top of a step. */
    private static final long JITTER_NANOS = UniformNotFound.RESPONSE_JITTER.toNanos();
    /** Room for timer and event-loop scheduling under machine load before an upper bound is reached. */
    private static final long SCHEDULING_SLACK_NANOS = TimeUnit.SECONDS.toNanos(5);
    /** The per-source answer hold, chosen well above the floor plus its jitter. */
    private static final int DELAY_MILLIS = 300;

    @Test
    @DisplayName("an open circuit is given Retry-After: 5, the seconds the breaker stays open")
    void openCircuitCarriesTheBreakerWindow() {
        PipelineRequest request = anyRequest();

        ErrorResponseWriter.seedRetryAfter(request, EventType.UPSTREAM_CIRCUIT_OPEN);

        Map<String, String> authored = request.gatewayAuthoredResponseHeaders();
        assertAll("Retry-After of an open circuit",
                () -> assertEquals("5", authored.get("Retry-After"),
                        "the header is a delay in whole seconds on the map every gateway-authored answer carries"),
                () -> assertEquals(Integer.toString(UpstreamGuards.BREAKER_RESET_SECONDS),
                        authored.get(ErrorResponseWriter.RETRY_AFTER_HEADER),
                        "the hint is the window the breaker's own delay is built from"));
    }

    @ParameterizedTest(name = "{0} gets no Retry-After")
    @EnumSource(value = EventType.class, mode = EnumSource.Mode.EXCLUDE, names = "UPSTREAM_CIRCUIT_OPEN")
    @DisplayName("control: no other event is given the header")
    void everyOtherEventIsLeftAlone(EventType eventType) {
        PipelineRequest request = anyRequest();

        ErrorResponseWriter.seedRetryAfter(request, eventType);

        assertFalse(request.gatewayAuthoredResponseHeaders().containsKey("Retry-After"),
                "only the open circuit's 503 names a time to retry at");
    }

    @Test
    @DisplayName("a rejection answered before a request was built is left without the header")
    void absentRequestIsTolerated() {
        assertDoesNotThrow(() -> ErrorResponseWriter.seedRetryAfter(null, EventType.UPSTREAM_CIRCUIT_OPEN));
    }

    private static PipelineRequest anyRequest() {
        return PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/" + Generators.letterStrings(1, 12).next())
                .build();
    }

    /**
     * The renderer driven over a live Vert.x server: a router handler builds the pipeline request the way
     * the stages leave it — a global header at selection, then the route's own headers, a challenge, an
     * {@code Allow}, a {@code Vary} and a stage cookie — and hands a rejection to the writer under test.
     */
    @Nested
    @DisplayName("the trust-boundary branch renders a pre-trust rejection as the unrouted 404")
    class TrustBoundaryBranch {

        private Vertx vertx;
        private HttpServer server;
        private HttpClient client;
        private GatewayEventCounter eventCounter;
        private SimpleMeterRegistry meterRegistry;
        private final AtomicReference<Consumer<RoutingContext>> handler = new AtomicReference<>();
        private String routeId;

        @BeforeEach
        void setUp() throws Exception {
            vertx = Vertx.vertx();
            eventCounter = new GatewayEventCounter();
            meterRegistry = new SimpleMeterRegistry();
            routeId = Generators.letterStrings(3, 10).next();
            Router router = Router.router(vertx);
            router.route().handler(ctx -> handler.get().accept(ctx));
            server = Awaits.connect(vertx.createHttpServer().requestHandler(router).listen(0, LoopbackHost.ADDRESS),
                    "the renderer test server to start listening");
            client = vertx.createHttpClient();
        }

        @AfterEach
        void tearDown() throws Exception {
            Awaits.teardown(client.close(), "the HTTP client to close");
            Awaits.teardown(server.close(), "the renderer test server to close");
            Awaits.teardown(vertx.close(), "Vert.x to close");
        }

        @Test
        @DisplayName("the response headers are restored to the selection snapshot and the stage cookies dropped")
        void restoresTheUnroutedHeadersAndDropsTheCookies() throws Exception {
            Response response = reject(writer(true, PortalEndpoint.inert()), Protocol.HTTP, List.of(),
                    EventType.METHOD_NOT_ALLOWED);

            assertAll("nothing a later stage authored survives",
                    () -> assertEquals(404, response.status()),
                    () -> assertEquals(PROBLEM_JSON, response.headers().get(CONTENT_TYPE)),
                    () -> assertEquals(noRouteMatchedBody(), response.body(),
                            "the NO_ROUTE_MATCHED body, without extension members"),
                    () -> assertEquals(GLOBAL_HSTS, response.headers().get(HSTS),
                            "the global header present at selection is restored"),
                    () -> assertNull(response.headers().get(FRAME_OPTIONS), "the route block is dropped"),
                    () -> assertNull(response.headers().get(WWW_AUTHENTICATE), "the challenge is dropped"),
                    () -> assertNull(response.headers().get(ALLOW), "the Allow header is dropped"),
                    () -> assertNull(response.headers().get(VARY), "the match.headers Vary is dropped"),
                    () -> assertNull(response.headers().get(SET_COOKIE), "the stage cookie is dropped"),
                    () -> assertNull(response.headers().get(CACHE_CONTROL),
                            "the no-store a cookie forced leaves with the cookie"));
        }

        @Test
        @DisplayName("a gRPC route is answered as an HTTP 404 problem, never a trailers-only status")
        void grpcRouteIsAnsweredAsHttp404() throws Exception {
            Response response = reject(writer(true, PortalEndpoint.inert()), Protocol.GRPC, List.of(),
                    EventType.TOKEN_MISSING);

            assertAll("the gRPC client is shown the unrouted answer",
                    () -> assertEquals(404, response.status()),
                    () -> assertEquals(PROBLEM_JSON, response.headers().get(CONTENT_TYPE)),
                    () -> assertNull(response.headers().get("grpc-status"), "no trailers-only status"),
                    () -> assertEquals(noRouteMatchedBody(), response.body()));
        }

        @Test
        @DisplayName("an explicit text/html is answered with the HTML page of the NO_ROUTE_MATCHED classification")
        void htmlPageFollowsTheNoRouteMatchedClassification() throws Exception {
            Response response = reject(writer(true, errorPagesPortal()), Protocol.HTTP, List.of("text/html"),
                    EventType.TOKEN_MISSING);

            assertAll("the bearer 401, keep-shape on its own, now negotiates as the unrouted 404 does",
                    () -> assertEquals(ErrorPageClassifier.Classification.HTML_ELIGIBLE,
                            ErrorPageClassifier.classify(EventType.NO_ROUTE_MATCHED),
                            "precondition: the unrouted 404 is HTML-eligible"),
                    () -> assertEquals(404, response.status()),
                    () -> assertTrue(response.headers().getOrDefault(CONTENT_TYPE, "").startsWith("text/html"),
                            String.valueOf(response.headers())),
                    () -> assertTrue(response.body().startsWith("<!DOCTYPE html>"), response.body()),
                    () -> assertNull(response.headers().get(WWW_AUTHENTICATE)));
        }

        @Test
        @DisplayName("rendered TO, not CALLED: the true event is counted and metered, NO_ROUTE_MATCHED is not")
        void theTrueEventIsReported() throws Exception {
            reject(writer(true, PortalEndpoint.inert()), Protocol.HTTP, List.of(), EventType.TOKEN_INVALID);

            Counter trueEvent = meterRegistry.find(SheriffMetrics.ERRORS_TOTAL)
                    .tags("route", routeId, "event", EventType.TOKEN_INVALID.name()).counter();
            assertAll("the server-side record names the real cause",
                    () -> assertEquals(1L, eventCounter.getCount(EventType.TOKEN_INVALID)),
                    () -> assertEquals(0L, eventCounter.getCount(EventType.NO_ROUTE_MATCHED)),
                    () -> assertNotNull(trueEvent, "sheriff_errors_total carries the true event"),
                    () -> assertEquals(1.0, trueEvent.count()),
                    () -> assertNull(meterRegistry.find(SheriffMetrics.ERRORS_TOTAL)
                                    .tag("event", EventType.NO_ROUTE_MATCHED.name()).counter(),
                            "the rejection is never re-reported as NO_ROUTE_MATCHED"));
        }

        @Test
        @DisplayName("switch off: the same verb refusal keeps its 405, its Allow header and the route block")
        void switchOffKeepsTheHonestAnswer() throws Exception {
            Response response = reject(writer(false, PortalEndpoint.inert()), Protocol.HTTP, List.of(),
                    EventType.METHOD_NOT_ALLOWED);

            assertAll("the honest answer",
                    () -> assertEquals(405, response.status()),
                    () -> assertEquals("GET", response.headers().get(ALLOW)),
                    () -> assertEquals("DENY", response.headers().get(FRAME_OPTIONS)),
                    () -> assertNull(response.headers().get(HSTS)),
                    () -> assertNotNull(response.headers().get(SET_COOKIE)));
        }

        @Test
        @DisplayName("switch off: a gRPC route keeps its trailers-only status")
        void switchOffKeepsTheTrailersOnlyStatus() throws Exception {
            Response response = reject(writer(false, PortalEndpoint.inert()), Protocol.GRPC, List.of(),
                    EventType.TOKEN_MISSING);

            assertAll("the trailers-only rejection",
                    () -> assertEquals(200, response.status()),
                    () -> assertEquals("16", response.headers().get("grpc-status")));
        }

        @Test
        @DisplayName("trust boundary passed: a closed-set event keeps its honest status")
        void trustedCallerKeepsTheHonestStatus() throws Exception {
            handler.set(ctx -> {
                PipelineRequest request = stagedRequest(Protocol.HTTP, List.of());
                request.markTrustBoundaryPassed();
                ctx.put(RejectionReporter.ROUTE_KEY, routeId);
                writer(true, PortalEndpoint.inert()).handleGatewayRejection(ctx, request,
                        new GatewayException(EventType.CONTENT_TOO_LARGE));
            });

            Response response = send();

            assertAll("a caller past the boundary is told the truth",
                    () -> assertEquals(413, response.status()),
                    () -> assertEquals("DENY", response.headers().get(FRAME_OPTIONS)));
        }

        @Test
        @DisplayName("an admission reading 1.5 floors old: the answer is written no earlier than the second floor step")
        void olderAdmissionIsHeldToTheSecondFloorStep() throws Exception {
            Timed timed = rejectAdmittedAt(nowNanos -> nowNanos - FLOOR_NANOS * 3 / 2);

            long writtenAfterAdmission = timed.writtenAfterAdmissionNanos();
            assertAll("released on the step grid measured from the admission reading",
                    () -> assertEquals(404, timed.status()),
                    () -> assertTrue(writtenAfterAdmission >= 2 * FLOOR_NANOS,
                            () -> "written " + writtenAfterAdmission + " ns after admission, before the second step of "
                                    + 2 * FLOOR_NANOS + " ns"),
                    () -> assertTrue(writtenAfterAdmission < 2 * FLOOR_NANOS + JITTER_NANOS + SCHEDULING_SLACK_NANOS,
                            () -> "written " + writtenAfterAdmission + " ns after admission, far past the second step"));
        }

        @Test
        @DisplayName("an admission reading one floor ahead of the writer's clock: the step is counted from that reading")
        void releaseIsCountedFromTheAdmissionReading() throws Exception {
            Timed timed = rejectAdmittedAt(nowNanos -> nowNanos + FLOOR_NANOS);

            long writtenAfterAdmission = timed.writtenAfterAdmissionNanos();
            assertAll("the first step after the admission reading, not after the writer's own reading",
                    () -> assertEquals(404, timed.status()),
                    () -> assertTrue(writtenAfterAdmission >= FLOOR_NANOS,
                            () -> "written " + writtenAfterAdmission + " ns after admission, before the first step of "
                                    + FLOOR_NANOS + " ns"),
                    () -> assertTrue(writtenAfterAdmission < FLOOR_NANOS + JITTER_NANOS + SCHEDULING_SLACK_NANOS,
                            () -> "written " + writtenAfterAdmission + " ns after admission, far past the first step"));
        }

        @Test
        @DisplayName("the HTML-page branch is released on the step grid of the admission reading as well")
        void htmlPageIsReleasedOnTheStepGrid() throws Exception {
            ErrorResponseWriter writer = writer(true, errorPagesPortal());

            Timed timed = timedAnswer(nowNanos -> nowNanos - FLOOR_NANOS * 3 / 2,
                    ctx -> writer.handleGatewayRejection(ctx, stagedRequest(Protocol.HTTP, List.of("text/html")),
                            new GatewayException(EventType.TOKEN_MISSING)));

            long writtenAfterAdmission = timed.writtenAfterAdmissionNanos();
            assertAll("the page, rendered before the release is computed, still leaves on the second step",
                    () -> assertEquals(404, timed.status()),
                    () -> assertTrue(timed.response().body().startsWith("<!DOCTYPE html>"),
                            "precondition: the HTML page answered"),
                    () -> assertTrue(writtenAfterAdmission >= 2 * FLOOR_NANOS,
                            () -> "written " + writtenAfterAdmission + " ns after admission, before the second step of "
                                    + 2 * FLOOR_NANOS + " ns"),
                    () -> assertTrue(writtenAfterAdmission < 2 * FLOOR_NANOS + JITTER_NANOS + SCHEDULING_SLACK_NANOS,
                            () -> "written " + writtenAfterAdmission + " ns after admission, far past the second step"));
        }

        @Test
        @DisplayName("an answer released on a step above the first is counted on the overrun meter")
        void overrunIsMetered() throws Exception {
            Timed timed = rejectAdmittedAt(nowNanos -> nowNanos - FLOOR_NANOS * 3 / 2);

            Counter overruns = meterRegistry.find(SheriffMetrics.UNIFORM_NOT_FOUND_OVERRUNS_TOTAL).counter();
            assertAll("an answer ready 1.5 floors after admission overran the first step",
                    () -> assertEquals(404, timed.status()),
                    () -> assertNotNull(overruns, "sheriff_uniform_not_found_overruns_total was recorded"),
                    () -> assertEquals(1.0, Objects.requireNonNull(overruns).count()));
        }

        @Test
        @DisplayName("control: an answer ready within the floor records no overrun")
        void answerWithinTheFloorRecordsNoOverrun() throws Exception {
            Timed timed = rejectAdmittedAt(nowNanos -> nowNanos);

            assertAll("released on the first step",
                    () -> assertEquals(404, timed.status()),
                    () -> assertNull(meterRegistry.find(SheriffMetrics.UNIFORM_NOT_FOUND_OVERRUNS_TOTAL).counter(),
                            "no overrun series exists"));
        }

        /**
         * Hands a pre-trust rejection to the writer with {@link ResponseDelay#ADMITTED_AT_KEY} set to the
         * admission reading {@code admissionFromNow} derives from the handler's own {@link System#nanoTime()},
         * and records when the response ended.
         */
        private Timed rejectAdmittedAt(LongUnaryOperator admissionFromNow) throws Exception {
            ErrorResponseWriter writer = writer(true, PortalEndpoint.inert());
            return timedAnswer(admissionFromNow, ctx -> writer.handleGatewayRejection(ctx,
                    stagedRequest(Protocol.HTTP, List.of()), new GatewayException(EventType.TOKEN_MISSING)));
        }

        /**
         * Runs {@code render} with {@link ResponseDelay#ADMITTED_AT_KEY} set to the admission reading
         * {@code admissionFromNow} derives from the handler's own {@link System#nanoTime()}, and records when
         * the response ended.
         */
        private Timed timedAnswer(LongUnaryOperator admissionFromNow, Consumer<RoutingContext> render)
                throws Exception {
            AtomicLong admittedAtNanos = new AtomicLong();
            CompletableFuture<Long> endedAtNanos = new CompletableFuture<>();
            handler.set(ctx -> {
                long admittedAt = admissionFromNow.applyAsLong(System.nanoTime());
                admittedAtNanos.set(admittedAt);
                ctx.put(ResponseDelay.ADMITTED_AT_KEY, admittedAt);
                ctx.put(RejectionReporter.ROUTE_KEY, routeId);
                ctx.addEndHandler(result -> endedAtNanos.complete(System.nanoTime()));
                render.accept(ctx);
            });

            Response response = send();

            long endedAt = Awaits.connect(endedAtNanos, "the held answer to end");
            return new Timed(response, endedAt - admittedAtNanos.get());
        }

        @Test
        @DisplayName("a RuntimeException after route selection and before the trust boundary is the floored unrouted 404")
        void preTrustInternalFailureIsTheFlooredUnrouted404() throws Exception {
            ErrorResponseWriter writer = writer(true, PortalEndpoint.inert());

            Timed timed = timedAnswer(nowNanos -> nowNanos, ctx -> writer.renderUnexpected(ctx,
                    stagedRequest(Protocol.HTTP, List.of()), new IllegalStateException(unexpectedMessage())));

            Response response = timed.response();
            long writtenAfterAdmission = timed.writtenAfterAdmissionNanos();
            assertAll("answered exactly as an unrouted address",
                    () -> assertEquals(404, response.status()),
                    () -> assertEquals(PROBLEM_JSON, response.headers().get(CONTENT_TYPE)),
                    () -> assertEquals(noRouteMatchedBody(), response.body(), "the NO_ROUTE_MATCHED body"),
                    () -> assertEquals(GLOBAL_HSTS, response.headers().get(HSTS),
                            "the response headers taken at selection are restored"),
                    () -> assertNull(response.headers().get(WWW_AUTHENTICATE), "the challenge is dropped"),
                    () -> assertNull(response.headers().get(SET_COOKIE), "the stage cookie is dropped"),
                    () -> assertTrue(writtenAfterAdmission >= FLOOR_NANOS,
                            () -> "written " + writtenAfterAdmission + " ns after admission, before the floor of "
                                    + FLOOR_NANOS + " ns"));
        }

        @Test
        @DisplayName("a pre-trust internal failure is counted under PRE_TRUST_INTERNAL_FAILURE, never as a 500")
        void preTrustInternalFailureIsCountedUnderItsOwnEvent() throws Exception {
            ErrorResponseWriter writer = writer(true, PortalEndpoint.inert());

            timedAnswer(nowNanos -> nowNanos, ctx -> writer.renderUnexpected(ctx,
                    stagedRequest(Protocol.HTTP, List.of()), new IllegalStateException(unexpectedMessage())));

            Counter counted = meterRegistry.find(SheriffMetrics.ERRORS_TOTAL)
                    .tags("route", routeId, "event", EventType.PRE_TRUST_INTERNAL_FAILURE.name()).counter();
            assertAll("the server-side record names the internal failure",
                    () -> assertEquals(1L, eventCounter.getCount(EventType.PRE_TRUST_INTERNAL_FAILURE)),
                    () -> assertEquals(0L, eventCounter.getCount(EventType.NO_ROUTE_MATCHED)),
                    () -> assertNotNull(counted, "sheriff_errors_total carries PRE_TRUST_INTERNAL_FAILURE"),
                    () -> assertEquals("routing", Objects.requireNonNull(counted).getId().getTag("category")),
                    () -> assertEquals(1.0, Objects.requireNonNull(counted).count()));
        }

        @Test
        @DisplayName("a pre-trust internal failure is admitted to the recon ledger under its own event")
        void preTrustInternalFailureIsAdmittedToTheReconLedger() throws Exception {
            SourceGate gate = sourceGate(EnumerationDetectionConfig.defaults());
            AtomicReference<SourceKey> stashed = new AtomicReference<>();
            handler.set(ctx -> {
                gate.answersBlocked(ctx);
                stashed.set(ctx.get(SourceGate.SOURCE_KEY));
                ctx.put(RejectionReporter.ROUTE_KEY, routeId);
                writer(true, PortalEndpoint.inert(), gate).renderUnexpected(ctx,
                        stagedRequest(Protocol.HTTP, List.of()), new IllegalStateException(unexpectedMessage()));
            });

            Response response = send();

            Map<Enum<?>, Long> contributions = gate.ledger().state(Objects.requireNonNull(stashed.get()))
                    .orElseThrow().contributions();
            assertAll("one more probe of the address",
                    () -> assertEquals(404, response.status()),
                    () -> assertEquals(Map.of(EventType.PRE_TRUST_INTERNAL_FAILURE, 1L), contributions));
        }

        @Test
        @DisplayName("past the trust boundary an internal failure stays the opaque, uncounted 500")
        void postTrustInternalFailureStays500() throws Exception {
            handler.set(ctx -> {
                PipelineRequest request = stagedRequest(Protocol.HTTP, List.of());
                request.markTrustBoundaryPassed();
                ctx.put(RejectionReporter.ROUTE_KEY, routeId);
                writer(true, PortalEndpoint.inert()).renderUnexpected(ctx, request,
                        new IllegalStateException(unexpectedMessage()));
            });

            Response response = send();

            assertAll("a trusted caller is told the truth",
                    () -> assertEquals(500, response.status()),
                    () -> assertEquals(0L, eventCounter.getCount(EventType.PRE_TRUST_INTERNAL_FAILURE)),
                    () -> assertNull(meterRegistry.find(SheriffMetrics.ERRORS_TOTAL).counter(),
                            "an uncategorized failure is not counted on sheriff_errors_total"));
        }

        @Test
        @DisplayName("switch off: a pre-trust internal failure stays the opaque 500")
        void switchOffKeepsTheInternalFailure500() throws Exception {
            handler.set(ctx -> {
                ctx.put(RejectionReporter.ROUTE_KEY, routeId);
                writer(false, PortalEndpoint.inert()).renderUnexpected(ctx,
                        stagedRequest(Protocol.HTTP, List.of()), new IllegalStateException(unexpectedMessage()));
            });

            Response response = send();

            assertAll("the honest internal error",
                    () -> assertEquals(500, response.status()),
                    () -> assertEquals(0L, eventCounter.getCount(EventType.PRE_TRUST_INTERNAL_FAILURE)));
        }

        @Test
        @DisplayName("before route selection an internal failure stays the opaque 500")
        void unroutedInternalFailureStays500() throws Exception {
            handler.set(ctx -> writer(true, PortalEndpoint.inert()).renderUnexpected(ctx,
                    PipelineRequest.builder().method(HttpMethod.POST).requestPath("/" + routeId + "/call").build(),
                    new IllegalStateException(unexpectedMessage())));

            Response response = send();

            assertAll("identical for every address already, so nothing to hide",
                    () -> assertEquals(500, response.status()),
                    () -> assertEquals(0L, eventCounter.getCount(EventType.PRE_TRUST_INTERNAL_FAILURE)));
        }

        private String unexpectedMessage() {
            return Generators.letterStrings(5, 20).next();
        }

        @Test
        @DisplayName("a pre-trust 401 rendered as the uniform 404 is admitted to the recon ledger under its true event")
        void preTrustRejectionIsAdmittedUnderItsTrueEvent() throws Exception {
            SourceGate gate = sourceGate(EnumerationDetectionConfig.defaults());
            AtomicReference<SourceKey> stashed = new AtomicReference<>();
            handler.set(ctx -> {
                gate.answersBlocked(ctx);
                stashed.set(ctx.get(SourceGate.SOURCE_KEY));
                ctx.put(RejectionReporter.ROUTE_KEY, routeId);
                writer(true, PortalEndpoint.inert(), gate).handleGatewayRejection(ctx,
                        stagedRequest(Protocol.HTTP, List.of()), new GatewayException(EventType.TOKEN_MISSING));
            });

            Response response = send();

            Map<Enum<?>, Long> contributions = gate.ledger().state(Objects.requireNonNull(stashed.get()))
                    .orElseThrow().contributions();
            assertAll("counted as what it is, whatever it is rendered as",
                    () -> assertEquals(404, response.status(), "precondition: rendered as the uniform 404"),
                    () -> assertEquals(Map.of(EventType.TOKEN_MISSING, 1L), contributions,
                            "admitted once, under TOKEN_MISSING and never under NO_ROUTE_MATCHED"));
        }

        @Test
        @DisplayName("a rejection past the trust boundary is not admitted to the recon ledger")
        void postTrustRejectionIsNotAdmitted() throws Exception {
            SourceGate gate = sourceGate(EnumerationDetectionConfig.defaults());
            AtomicReference<SourceKey> stashed = new AtomicReference<>();
            handler.set(ctx -> {
                gate.answersBlocked(ctx);
                stashed.set(ctx.get(SourceGate.SOURCE_KEY));
                PipelineRequest request = stagedRequest(Protocol.HTTP, List.of());
                request.markTrustBoundaryPassed();
                ctx.put(RejectionReporter.ROUTE_KEY, routeId);
                writer(true, PortalEndpoint.inert(), gate).handleGatewayRejection(ctx, request,
                        new GatewayException(EventType.CONTENT_TOO_LARGE));
            });

            Response response = send();

            assertAll("a trusted caller's rejection is no recon signal",
                    () -> assertEquals(413, response.status(), "precondition: the honest answer"),
                    () -> assertTrue(gate.ledger().state(Objects.requireNonNull(stashed.get())).isEmpty(),
                            "the source was never admitted, so the ledger does not track it"));
        }

        @Test
        @DisplayName("delay: a tripped source's rejection is answered no earlier than admission plus delay_millis")
        void trippedSourceUnderDelayIsHeldForTheDelay() throws Exception {
            long delayNanos = TimeUnit.MILLISECONDS.toNanos(DELAY_MILLIS);
            SourceGate gate = sourceGate(EnumerationDetectionConfig.builder()
                    .threshold(1).action(EnumerationDetectionConfig.Action.DELAY).delayMillis(DELAY_MILLIS).build());
            ErrorResponseWriter writer = writer(true, PortalEndpoint.inert(), gate);
            AtomicLong admittedAtNanos = new AtomicLong();
            CompletableFuture<Long> endedAtNanos = new CompletableFuture<>();
            handler.set(ctx -> {
                long admittedAt = System.nanoTime();
                admittedAtNanos.set(admittedAt);
                ctx.put(ResponseDelay.ADMITTED_AT_KEY, admittedAt);
                gate.answersBlocked(ctx);
                ctx.put(RejectionReporter.ROUTE_KEY, routeId);
                ctx.addEndHandler(result -> endedAtNanos.complete(System.nanoTime()));
                writer.handleGatewayRejection(ctx, stagedRequest(Protocol.HTTP, List.of()),
                        new GatewayException(EventType.METHOD_NOT_ALLOWED));
            });

            Response response = send();

            long writtenAfterAdmission = Awaits.connect(endedAtNanos, "the held answer to end") - admittedAtNanos.get();
            assertAll("held past the uniform 404's floor, to the per-source delay",
                    () -> assertEquals(404, response.status(), "the answer itself is unchanged"),
                    () -> assertTrue(delayNanos > FLOOR_NANOS + JITTER_NANOS,
                            "precondition: the delay outlasts the floor, so it is the later release time"),
                    () -> assertTrue(writtenAfterAdmission >= delayNanos,
                            () -> "written " + writtenAfterAdmission + " ns after admission, before the "
                                    + delayNanos + " ns delay"),
                    () -> assertTrue(writtenAfterAdmission < delayNanos + SCHEDULING_SLACK_NANOS,
                            () -> "written " + writtenAfterAdmission + " ns after admission, far past the delay"));
        }

        private SourceGate sourceGate(EnumerationDetectionConfig config) {
            ForwardedHeaderResolver forwardedResolver = new ForwardedHeaderResolver(
                    ForwardedResolverConfig.builder().trustedProxies(Set.of())
                            .securityConfig(SecurityConfiguration.defaults()).build(),
                    new SecurityEventCounter());
            return new SourceGate(config, new SourceKeyResolver(List.of()), new TcpPeerGate(List.of()),
                    forwardedResolver, eventCounter, new SheriffMetrics(meterRegistry),
                    new GatewayJson(new ObjectMapper()));
        }

        private ErrorResponseWriter writer(boolean uniformNotFound, PortalEndpoint portal) {
            return writer(uniformNotFound, portal, ErrorResponseWriter.ReconFeed.NONE);
        }

        private ErrorResponseWriter writer(boolean uniformNotFound, PortalEndpoint portal,
                ErrorResponseWriter.ReconFeed reconFeed) {
            RejectionReporter reporter = new RejectionReporter(eventCounter, new SheriffMetrics(meterRegistry),
                    1_000L, 1_000L);
            return new ErrorResponseWriter(portal, new GatewayJson(new ObjectMapper()), new GrpcStatusMapper(),
                    reporter, uniformNotFound, reconFeed);
        }

        private Response reject(ErrorResponseWriter writer, Protocol protocol, List<String> accept,
                EventType eventType) throws Exception {
            handler.set(ctx -> {
                ctx.put(RejectionReporter.ROUTE_KEY, routeId);
                writer.handleGatewayRejection(ctx, stagedRequest(protocol, accept), new GatewayException(eventType));
            });
            return send();
        }

        /**
         * A request in the state the stages leave it in: the global HSTS present at route selection, then
         * stage 2a's route block replacing it, the verb gate's {@code Allow}, the bearer challenge, a
         * {@code match.headers} {@code Vary} and a stage cookie.
         */
        private PipelineRequest stagedRequest(Protocol protocol, List<String> accept) {
            PipelineRequest request = PipelineRequest.builder()
                    .method(HttpMethod.POST)
                    .requestPath("/" + routeId + "/call")
                    .queryParameters(List.of())
                    .headers(accept.isEmpty() ? Map.of() : Map.of("accept", accept))
                    .build();
            request.canonicalPath("/" + routeId + "/call");
            request.responseHeaders().put(HSTS, GLOBAL_HSTS);
            request.selectedRoute(Route.builder()
                    .id(routeId)
                    .match(MatchConfig.builder().pathPrefix("/" + routeId).build())
                    .protocol(protocol)
                    .effectiveAuth(AuthConfig.builder().require(Require.BEARER).build())
                    .upstream(new ResolvedUpstream("https", "orders.example", 443, ""))
                    .build());
            request.responseHeaders().remove(HSTS);
            request.responseHeaders().put(FRAME_OPTIONS, "DENY");
            request.responseHeaders().put(ALLOW, "GET");
            request.responseHeaders().put(WWW_AUTHENTICATE, "Bearer");
            request.responseHeaders().put(VARY, "X-Tenant");
            request.addResponseSetCookie("stage=" + Generators.letterStrings(4, 10).next() + "; Path=/");
            return request;
        }

        private PortalEndpoint errorPagesPortal() {
            PortalConfig portal = PortalConfig.builder().path("/portal").title("Portal").errorPages(true).build();
            return PortalEndpoint.of(portal, new PortalCatalog(List.of()), PortalRenderer.builtIn(),
                    (cookie, now) -> SessionIdentity.anonymous(), null, false, "/");
        }

        private String noRouteMatchedBody() {
            EventCategory routing = Objects.requireNonNull(EventType.NO_ROUTE_MATCHED.category());
            return ErrorResponseWriter.problemBody(new GatewayJson(new ObjectMapper()), routing.problemType(),
                    routing.title(), 404, Map.of());
        }

        private Response send() throws Exception {
            RequestOptions options = new RequestOptions().setHost(LoopbackHost.ADDRESS)
                    .setPort(server.actualPort()).setMethod(io.vertx.core.http.HttpMethod.POST)
                    .setURI("/" + routeId + "/call");
            CompletableFuture<Response> future = client.request(options)
                    .compose(HttpClientRequest::send)
                    .compose(response -> {
                        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                        response.headers().forEach(entry -> headers.putIfAbsent(entry.getKey(), entry.getValue()));
                        return response.body().map(buffer -> new Response(response.statusCode(), headers,
                                buffer == null ? "" : buffer.toString()));
                    })
                    .toCompletionStage().toCompletableFuture();
            return Awaits.connect(future, "the rendered rejection");
        }
    }

    /** The terminal response, captured once its body has fully arrived. */
    private record Response(int status, Map<String, String> headers, String body) {
    }

    /** A held answer and how long after its admission reading the response ended. */
    private record Timed(Response response, long writtenAfterAdmissionNanos) {

        int status() {
            return response.status();
        }
    }
}
