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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EdgeHardeningConfig;
import de.cuioss.sheriff.gateway.config.model.EnumerationDetectionConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.ConfigFixtures;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.TestTokenHolder;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The held-answer sub-budget through the real edge: an answer held back on a timer — a uniform
 * {@code 404}, whether the address is unrouted or a routed path refused before the trust boundary —
 * leaves the general admission pool for a sub-budget of its own, so a caller producing held answers
 * cannot starve the traffic the gateway exists to serve.
 * <p>
 * <strong>Making the hold long enough to observe.</strong> The uniform {@code 404}'s floor is far
 * too short to keep answers held while a test inspects the pools, so the edge runs
 * {@code enumeration_detection} with {@code action: delay} and a threshold of one: once the source has
 * tripped, each of its rejection answers is held for {@value #HOLD_MILLIS} ms through the same
 * {@code ResponseDelay} hold the floor uses.
 * <p>
 * <strong>The sizes.</strong> {@code admission_cap} is {@value #ADMISSION_CAP}, so the held-answer
 * sub-budget is one permit and the whole general pool fits through the stub upstream's and the test
 * client's connection pools at once. A burst of {@value #ADMISSION_CAP} rejections, unrouted and
 * routed alternately, therefore has one answer held and the rest refused {@code 503} at once — at
 * least one of each kind. While that one answer is held, {@value #ADMISSION_CAP} authenticated requests
 * against an upstream that answers only when the test lets it are all admitted: the held answer handed
 * its general permit back. Were it still holding the permit, one of them would be refused.
 */
@DisplayName("GatewayEdgeRoute — held answers are bounded by their own admission sub-budget")
@Tag("isolated-fork")
class GatewayEdgeHeldAnswerBudgetTest {

    private static final int ADMISSION_CAP = 4;
    private static final int HELD_ANSWER_CAP = 1;
    private static final long HOLD_MILLIS = 2_000L;
    private static final String UNROUTED = "/nowhere/probe";
    private static final String SECURE = "/secure/data";
    private static final String SLOW = "/secure/slow";
    private static final Duration ADMITTED_WAIT = Duration.ofSeconds(1);

    private Vertx vertx;
    private ExecutorService virtualThreadExecutor;
    private HttpServer upstreamServer;
    private @Nullable HttpServer front;
    private HttpClient client;
    private String bearer;
    /** Released by the test: requests to {@value #SLOW} are answered once it completes. */
    private final AtomicReference<CompletableFuture<Void>> slowGate = new AtomicReference<>(new CompletableFuture<>());
    private final AtomicInteger slowArrived = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        upstreamServer = Awaits.connect(vertx.createHttpServer().requestHandler(this::handleUpstream)
                .listen(0, LoopbackHost.ADDRESS), "the stub upstream server to start listening");
        client = vertx.createHttpClient(new HttpClientOptions(), new PoolOptions().setHttp1MaxSize(4 * ADMISSION_CAP));
        TestTokenHolder token = TestTokenGenerators.accessTokens().next();
        bearer = token.getRawToken();
        start(TokenValidator.builder().issuerConfig(token.getIssuerConfig()).build());
    }

    @AfterEach
    void tearDown() throws Exception {
        slowGate.get().complete(null);
        Awaits.teardown(client.close(), "the HTTP client to close");
        if (front != null) {
            Awaits.teardown(front.close(), "the edge front server to close");
        }
        Awaits.teardown(upstreamServer.close(), "the stub upstream server to close");
        virtualThreadExecutor.close();
        Awaits.teardown(vertx.close(), "Vert.x to close");
    }

    private void handleUpstream(HttpServerRequest request) {
        request.body().onComplete(ignored -> {
            if (request.path().contains("/slow")) {
                slowArrived.incrementAndGet();
                slowGate.get().thenRun(() -> request.response().end("slow"));
            } else {
                request.response().end("upstream");
            }
        });
    }

    @Test
    @DisplayName("held answers past the sub-budget are refused 503 at once, alike for both kinds, while the general pool stays free")
    void heldAnswersPastTheSubBudgetAreRefusedAndTheGeneralPoolStaysFree() throws Exception {
        assertEquals(404, send(UNROUTED, Map.of()).status(), "precondition: the source trips on its first rejection");

        Burst burst = burst();
        Awaitility.await().atMost(Duration.ofMillis(HOLD_MILLIS / 2))
                .until(() -> burst.refused().size() == ADMISSION_CAP - HELD_ANSWER_CAP);
        List<Integer> whileHeld = authenticatedRound();
        List<Timed> answers = burst.all();

        List<Timed> held = answers.stream().filter(answer -> answer.response().status() == 404).toList();
        List<Timed> refused = answers.stream().filter(answer -> answer.response().status() == 503).toList();
        List<Response> refusedUnrouted = refused.stream().filter(answer -> UNROUTED.equals(answer.uri()))
                .map(Timed::response).toList();
        List<Response> refusedRouted = refused.stream().filter(answer -> SECURE.equals(answer.uri()))
                .map(Timed::response).toList();
        assertAll("a burst of held answers against a sub-budget of one",
                () -> assertEquals(HELD_ANSWER_CAP, held.size(), () -> "one answer is held: " + answers),
                () -> assertEquals(ADMISSION_CAP - HELD_ANSWER_CAP, refused.size(), () -> "the rest is refused: " + answers),
                () -> assertTrue(held.stream().allMatch(answer -> answer.elapsedMillis() >= HOLD_MILLIS),
                        () -> "the held answer waited its hold: " + held),
                () -> assertTrue(refused.stream().allMatch(answer -> answer.elapsedMillis() < HOLD_MILLIS),
                        () -> "a refused answer is written at once, without waiting: " + refused),
                () -> assertTrue(!refusedUnrouted.isEmpty() && !refusedRouted.isEmpty(),
                        () -> "both kinds were refused: " + refused),
                () -> assertEquals(refusedUnrouted.getFirst(), refusedRouted.getFirst(),
                        "an unrouted and a routed refusal are answered byte for byte alike"),
                () -> assertEquals(List.of(200, 200, 200, 200), whileHeld,
                        "while the answer is held every general permit is free: the authenticated requests are all served"));
    }

    @Test
    @DisplayName("once the held answers have ended, every general and every held permit is back")
    void everyPermitIsBackOnceTheHeldAnswersEnded() throws Exception {
        assertEquals(404, send(UNROUTED, Map.of()).status(), "precondition: the source trips on its first rejection");
        burst().all();

        List<Integer> afterTheHold = authenticatedRound();
        List<Timed> second = burst().all();

        assertAll("the pools after a held burst",
                () -> assertEquals(List.of(200, 200, 200, 200), afterTheHold,
                        "the whole general pool admits again"),
                () -> assertEquals(HELD_ANSWER_CAP, second.stream().filter(answer -> answer.response().status() == 404)
                        .count(), () -> "the held permit is back for the next burst: " + second),
                () -> assertEquals(ADMISSION_CAP - HELD_ANSWER_CAP, second.stream()
                        .filter(answer -> answer.response().status() == 503).count(),
                        () -> "and the rest of the next burst is refused again: " + second));
    }

    /**
     * Sends {@value #ADMISSION_CAP} authenticated requests at once to the gated upstream path, waits
     * until each was admitted — it reached the upstream — or was answered, then lets the upstream
     * answer and returns the statuses.
     */
    private List<Integer> authenticatedRound() throws Exception {
        slowGate.set(new CompletableFuture<>());
        slowArrived.set(0);
        List<CompletableFuture<Response>> round = new ArrayList<>();
        for (int index = 0; index < ADMISSION_CAP; index++) {
            round.add(sendAsync(SLOW + "-" + index, Map.of("Authorization", "Bearer " + bearer)));
        }
        Awaitility.await().atMost(ADMITTED_WAIT).until(() -> slowArrived.get() == ADMISSION_CAP
                || round.stream().anyMatch(CompletableFuture::isDone));
        slowGate.get().complete(null);
        List<Integer> statuses = new ArrayList<>();
        for (CompletableFuture<Response> response : round) {
            statuses.add(Awaits.connect(response, "an authenticated request to be answered").status());
        }
        return statuses;
    }

    /** Sends {@value #ADMISSION_CAP} rejections at once, alternately unrouted and routed before the trust boundary. */
    private Burst burst() {
        List<TimedFuture> sent = new ArrayList<>();
        for (int index = 0; index < ADMISSION_CAP; index++) {
            String uri = index % 2 == 0 ? UNROUTED : SECURE;
            long sentAt = System.nanoTime();
            sent.add(new TimedFuture(uri, sendAsync(uri, Map.of())
                    .thenApply(response -> new Timed(uri, response,
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sentAt)))));
        }
        return new Burst(sent);
    }

    private void start(TokenValidator tokenValidator) throws Exception {
        front = EdgeFixtures.edge(vertx, virtualThreadExecutor)
                .gatewayConfig(ConfigFixtures.gateway().enumerationDetection(EnumerationDetectionConfig.builder()
                        .threshold(1).action(EnumerationDetectionConfig.Action.DELAY).delayMillis((int) HOLD_MILLIS)
                        .build()).build())
                .hardening(new EdgeHardeningOptions(new EdgeHardeningConfig(ADMISSION_CAP, null)))
                .tokenValidator(tokenValidator)
                .routes(ConfigFixtures.route("secure", "/secure")
                        .effectiveAuth(AuthConfig.builder().require(Require.BEARER).build())
                        .upstream(new ResolvedUpstream("http", LoopbackHost.ADDRESS, upstreamServer.actualPort(), ""))
                        .build())
                .serve();
    }

    private Response send(String uri, Map<String, String> headers) throws Exception {
        return Awaits.connect(sendAsync(uri, headers), "the edge response to GET " + uri);
    }

    private CompletableFuture<Response> sendAsync(String uri, Map<String, String> requestHeaders) {
        RequestOptions options = new RequestOptions().setHost(LoopbackHost.ADDRESS)
                .setPort(Objects.requireNonNull(front).actualPort()).setURI(uri);
        return client.request(options)
                .compose(request -> {
                    requestHeaders.forEach(request::putHeader);
                    return request.send();
                })
                .compose(response -> {
                    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                    response.headers().forEach(entry -> headers.putIfAbsent(entry.getKey(), entry.getValue()));
                    // Date differs between any two answers; it is not part of what makes two answers alike.
                    headers.remove("Date");
                    return response.body().map(buffer -> new Response(response.statusCode(), headers,
                            buffer == null ? "" : buffer.toString()));
                })
                .toCompletionStage().toCompletableFuture();
    }

    /** The terminal response, captured once its body has fully arrived. */
    private record Response(int status, Map<String, String> headers, String body) {
    }

    /** An answer, the address it was sent to and how long it took. */
    private record Timed(String uri, Response response, long elapsedMillis) {
    }

    /** An answer still on its way. */
    private record TimedFuture(String uri, CompletableFuture<Timed> answer) {
    }

    /** The requests of one burst. */
    private record Burst(List<TimedFuture> sent) {

        /** The answers already refused {@code 503}. */
        List<Timed> refused() {
            return sent.stream().map(TimedFuture::answer).filter(CompletableFuture::isDone)
                    .map(CompletableFuture::join).filter(answer -> answer.response().status() == 503).toList();
        }

        /** Every answer of the burst, waiting for the held ones. */
        List<Timed> all() throws Exception {
            List<Timed> answers = new ArrayList<>();
            for (TimedFuture future : sent) {
                answers.add(Awaits.connect(future.answer(), "an answer of the burst to " + future.uri()));
            }
            return answers;
        }
    }
}
