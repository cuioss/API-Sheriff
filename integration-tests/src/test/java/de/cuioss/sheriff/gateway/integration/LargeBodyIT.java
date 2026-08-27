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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import de.cuioss.sheriff.gateway.integration.MtlsHandshakeIT.TrustAllManager;

import io.restassured.path.json.JsonPath;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the body-size contract end-to-end across the <em>real</em> framework boundary: a body larger
 * than the Vert.x default is accepted and forwarded, and a body larger than the route's declared
 * {@code security_filter.max_body_bytes} is rejected by the <em>gateway</em> with its own RFC 9457
 * envelope rather than by the framework with a bare status line.
 * <p>
 * Both cases run against the {@code /upload/small} route, which rides the {@code upload} anchor's
 * declared cap of 64 MiB (67108864). Three descriptor facts have to line up for this suite to mean
 * what it claims, and each is what a corresponding failure would expose:
 * <ol>
 *   <li>{@code quarkus.http.limits.max-body-size} in {@code application.properties} raises the
 *       framework floor above Vert.x' 10 MiB default. Revert it and the positive case's 11 MiB body
 *       is clipped by the framework before the gateway pipeline ever runs.</li>
 *   <li>The compose stack's {@code QUARKUS_HTTP_LIMITS_MAX_BODY_SIZE=128M} override on the
 *       {@code api-sheriff} service lifts that floor <em>above</em> the negative case's 68 MiB body.
 *       Drop or lower it and the Quarkus root handler answers the oversize request with a bare 413
 *       before the gateway runs, silently turning the negative case into an assertion about the
 *       framework instead of about the gateway's envelope.</li>
 *   <li>go-httpbin's {@code -max-body-size 67108864} lifts the upstream's own 1 MiB default above the
 *       positive case's body. Drop it and the 11 MiB forward is rejected upstream, not at the edge.</li>
 * </ol>
 * The fast, no-Docker sibling that asserts those descriptor facts directly is
 * {@code BodyLimitActivationWiringTest}; this suite is the containerised proof of the behaviour they
 * enable.
 * <p>
 * <strong>Why the negative case is not driven with REST Assured.</strong> It must declare a
 * {@code Content-Length} it never actually writes, and it must observe that no body bytes were
 * produced. That needs {@code Expect: 100-continue} plus a body publisher whose produced bytes are
 * counted — a shape REST Assured does not expose. The JDK {@link HttpClient} does: a publisher
 * reporting a {@link HttpRequest.BodyPublisher#contentLength() contentLength} of 68 MiB makes the
 * client emit that {@code Content-Length} header, and {@code expectContinue(true)} holds the body back
 * until the server invites it. A gateway that rejects on the declared length never sends the invite,
 * so the counter stays at {@code 0} — the observable proof the rejection happened <em>before</em> any
 * payload crossed the wire, not after 68 MiB were uploaded and discarded.
 * <p>
 * The negative case asserts <strong>both</strong> halves of the error contract, and neither replaces
 * the other. The status assertion locks the mapping under test: a proxy-path body-cap breach renders
 * {@code CONTENT_TOO_LARGE} as HTTP {@code 413}, so a regression that moved the event back to a
 * 400-mapped constant fails here. The envelope assertions — the {@code application/problem+json}
 * content type, the input-validation problem type and title, and the absence of the go-httpbin echo —
 * are what discriminate the <em>gateway's</em> rejection from the <em>framework's</em>: the framework's
 * own {@code Content-Length} pre-check answers with a bare {@code 413} carrying no envelope, so the
 * status alone cannot tell the two apart.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class LargeBodyIT extends BaseIntegrationTest {

    /**
     * 11 MiB — above Vert.x' 10 MiB default and below the {@code upload} anchor's 64 MiB cap, so it is
     * accepted only when the framework floor has actually been raised.
     */
    private static final int POSITIVE_BODY_BYTES = 11534336;

    /**
     * 68 MiB — above the {@code upload} anchor's 64 MiB cap and below the compose stack's 128 MiB
     * framework floor, so the rejection is the gateway's and not the framework's.
     */
    private static final long NEGATIVE_BODY_BYTES = 71303168L;

    private static final String UPLOAD_PATH = "/upload/small";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String INPUT_VALIDATION_TYPE = "urn:api-sheriff:problem:input-validation";

    private static HttpClient httpClient;

    @BeforeAll
    static void setUpLargeBodyClient() throws Exception {
        // Trust-all TLS for the stack's self-signed localhost certificate — the JDK client's
        // equivalent of BaseIntegrationTest's RestAssured.useRelaxedHTTPSValidation(). Scoped to this
        // black-box IT against a throwaway local certificate; never a production trust decision.
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
        httpClient = HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Test
    @DisplayName("an 11 MiB body under the route cap is accepted and forwarded to the upstream")
    void bodyUnderTheRouteCapIsForwarded() {
        byte[] payload = new byte[POSITIVE_BODY_BYTES];
        Arrays.fill(payload, (byte) 'a');

        var response = given()
                .contentType("text/plain")
                .body(payload)
                .when()
                .post(UPLOAD_PATH)
                .then()
                .statusCode(200)
                .extract();

        assertNotNull(response.path("method"),
                "an accepted body must reach the go-httpbin upstream — its echo carries the method");
    }

    @Test
    @DisplayName("a 68 MiB declared body over the route cap is rejected by the gateway before any payload")
    void bodyOverTheRouteCapIsRejectedByTheGateway() throws Exception {
        CountingBodyPublisher body = new CountingBodyPublisher(NEGATIVE_BODY_BYTES);
        HttpRequest request = HttpRequest.newBuilder(URI.create(uploadUri()))
                .header("Content-Type", "text/plain")
                .timeout(Duration.ofSeconds(30))
                .expectContinue(true)
                .POST(body)
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(413, response.statusCode(),
                "an over-cap request must render CONTENT_TOO_LARGE as HTTP 413");

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        JsonPath problem = JsonPath.from(response.body());
        assertTrue(contentType.contains(PROBLEM_JSON),
                "the gateway must render its own RFC 9457 envelope, Content-Type was: " + contentType);
        // Use the typed getString accessor rather than the generic get(): inside String.valueOf(...)
        // javac resolves JsonPath's <T> T get(String) against the most specific overload,
        // String.valueOf(char[]), inferring T = char[] and failing with a ClassCastException at runtime.
        String problemType = problem.getString("type");
        assertTrue(String.valueOf(problemType).contains(INPUT_VALIDATION_TYPE),
                "an oversize body is an input-validation rejection, type was: " + problemType);
        assertEquals("Input Validation", problem.getString("title"));
        assertNull(problem.get("method"), "a rejected request must not reach the go-httpbin upstream");
        assertEquals(0L, body.producedBytes(),
                "the rejection must land on the declared Content-Length, before the 100-continue invite —"
                        + " a non-zero count means the gateway accepted the payload and discarded it afterwards");
    }

    private static String uploadUri() {
        String testPort = System.getProperty("test.https.port", "10443");
        return "https://localhost:" + testPort + UPLOAD_PATH;
    }

    /**
     * A body publisher that <em>declares</em> {@code contentLength} bytes and counts how many it is
     * actually asked to produce. Paired with {@code Expect: 100-continue}, the count is the observable
     * proof of where the rejection landed: {@code 0} means the server refused on the declared length
     * alone and never invited the payload.
     */
    private static final class CountingBodyPublisher implements HttpRequest.BodyPublisher {

        private final long declaredLength;
        private final AtomicLong produced = new AtomicLong();

        CountingBodyPublisher(long declaredLength) {
            this.declaredLength = declaredLength;
        }

        @Override
        public long contentLength() {
            return declaredLength;
        }

        long producedBytes() {
            return produced.get();
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            subscriber.onSubscribe(new ChunkedSubscription(subscriber, declaredLength, produced));
        }
    }

    /**
     * Emits zero-filled chunks on demand up to the declared length, tallying every byte handed out.
     * The drain loop is guarded so a re-entrant {@code request(n)} from inside {@code onNext} does not
     * recurse.
     */
    private static final class ChunkedSubscription implements Flow.Subscription {

        private static final int CHUNK_BYTES = 64 * 1024;

        private final Flow.Subscriber<? super ByteBuffer> subscriber;
        private final AtomicLong produced;
        private final AtomicLong demand = new AtomicLong();
        private final AtomicBoolean draining = new AtomicBoolean();
        private volatile boolean finished;
        private volatile long remaining;

        ChunkedSubscription(Flow.Subscriber<? super ByteBuffer> subscriber, long length, AtomicLong produced) {
            this.subscriber = subscriber;
            this.produced = produced;
            this.remaining = length;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                finished = true;
                subscriber.onError(new IllegalArgumentException("non-positive subscription request: " + n));
                return;
            }
            demand.addAndGet(n);
            drain();
        }

        @Override
        public void cancel() {
            finished = true;
        }

        private void drain() {
            if (!draining.compareAndSet(false, true)) {
                return;
            }
            try {
                while (!finished && demand.get() > 0 && remaining > 0) {
                    demand.decrementAndGet();
                    int chunk = (int) Math.min(CHUNK_BYTES, remaining);
                    remaining -= chunk;
                    produced.addAndGet(chunk);
                    subscriber.onNext(ByteBuffer.allocate(chunk));
                }
                if (!finished && remaining == 0) {
                    finished = true;
                    subscriber.onComplete();
                }
            } finally {
                draining.set(false);
            }
        }
    }
}
