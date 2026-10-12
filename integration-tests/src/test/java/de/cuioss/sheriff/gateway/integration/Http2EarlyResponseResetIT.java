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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.StreamResetException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Many HTTP/2 uploads the upstream answers <em>before</em> reading their body, sent over one
 * connection while they are still sending, stay answered and leave that connection open.
 * <p>
 * <strong>The defect shape.</strong> The server counts resets per connection — Quarkus/Netty's
 * Rapid-Reset guard, by default 200 in a 30-second window — and above that answers
 * {@code GOAWAY ENHANCE_YOUR_CALM} and drops the connection with every sibling stream on it. Two kinds
 * of reset feed it when an upload is answered while its body is still open:
 * <ul>
 *   <li>the client's: a Go-style client (k6 among them) that sees the complete response on a stream it
 *       is still uploading on resets the stream itself with {@code RST_STREAM(CANCEL)};</li>
 *   <li>the server's own: request data still on the way when the stream is reset arrives on a closed
 *       stream, and the transport answers each such data frame with {@code RST_STREAM(STREAM_CLOSED)},
 *       counted against the same limit.</li>
 * </ul>
 * A burst of perfectly ordinary early-answered uploads is enough to trip it either way.
 * <p>
 * <strong>What the gateway does instead.</strong> Once the answer is decided it stops reading the body
 * and stops granting flow-control window for it, holds the end of the response back until no request
 * data can still arrive (bounded), then ends it and resets the stream itself with {@code NO_ERROR}
 * (RFC 9113 §8.1.1): the client is told to stop sending, does not reset the stream on its own, and has
 * nothing in flight that would draw a {@code STREAM_CLOSED} reset.
 * <p>
 * <strong>Why this client keeps sending.</strong> Every upload writes its body chunk after chunk, as
 * fast as flow control lets it, until its stream ends — so request data is in flight when the answer
 * arrives, as it is for a real uploading client. Against a gateway that resets right after writing the
 * answer, the data frames still on the way draw one {@code STREAM_CLOSED} reset each, {@value #UPLOADS}
 * uploads produce far more than the limit, and the guard fires.
 * <p>
 * <strong>Why this client defers its own reset.</strong> Go's client resets the stream as soon as it
 * has the response, racing the gateway's reset. That race is not deterministic, so this client stands
 * in for it in a form that is: per stream it waits a short, bounded grace for the gateway's
 * {@code NO_ERROR} reset and only when none arrives resets the stream itself with {@code CANCEL}.
 * Against a gateway without its own reset every stream therefore ends in a client reset and the guard
 * fires; against a gateway with it, the client never resets a stream at all.
 * <p>
 * The uploads go to {@code /upload/early-answer}, whose upstream {@code early-answer-backend} answers
 * {@code 200} without reading the body (see {@code sheriff-config/endpoints/early-answer.yaml}).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("HTTP/2 uploads answered before their body ended stay answered on one connection, without GOAWAY")
class Http2EarlyResponseResetIT {

    private static final String DEFAULT_TEST_PORT = "10443";
    private static final String UPLOAD_PATH = "/upload/early-answer";

    /** Well above the resets the Rapid-Reset guard admits per window, in either direction. */
    private static final int UPLOADS = 400;
    /** Uploads in flight at once on the one connection; the client works through them in batches. */
    private static final int BATCH = 10;
    /** The Rapid-Reset guard's window: the whole run must fall inside it to stand for one window. */
    private static final Duration GUARD_WINDOW = Duration.ofSeconds(30);
    /** How long a stream waits for the gateway's reset after its response before resetting itself. */
    private static final long GATEWAY_RESET_GRACE_MILLIS = 500L;
    /** Upper bound for one batch to settle, far above a grace and a round trip. */
    private static final long BATCH_TIMEOUT_SECONDS = 20L;

    /** The HTTP/2 error code {@code NO_ERROR}. */
    private static final long HTTP2_NO_ERROR = 0L;
    /** The HTTP/2 error code {@code CANCEL}, the reset a Go-style client sends. */
    private static final long HTTP2_CANCEL = 0x8L;

    /** The chunk every upload writes again and again while its stream is open. Its content is irrelevant. */
    private static final Buffer CHUNK = Buffer.buffer("x".repeat(16 * 1024));
    /** What one upload sends at most: far beyond any flow-control window, so the gateway is what stops it. */
    private static final long UPLOAD_CAP_BYTES = 8L * 1024 * 1024;

    private final List<Long> goAwayCodes = new CopyOnWriteArrayList<>();
    private final AtomicBoolean connectionClosed = new AtomicBoolean();
    private final Set<HttpConnection> connections = ConcurrentHashMap.newKeySet();

    @Test
    @DisplayName("every early-answered upload is answered 200 and reset by the gateway with NO_ERROR, on one open connection")
    void earlyAnsweredUploadsStayAnsweredWithoutGoAway() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            HttpClient client = vertx.createHttpClient(new HttpClientOptions()
                    .setProtocolVersion(HttpVersion.HTTP_2)
                    .setSsl(true)
                    .setUseAlpn(true)
                    // The stack serves its self-signed localhost certificate; trusting it blindly is
                    // scoped to this black-box IT and is never a production trust decision.
                    .setTrustAll(true)
                    .setVerifyHost(false), new PoolOptions().setHttp2MaxSize(1));
            List<StreamOutcome> outcomes = new ArrayList<>();
            long started = System.nanoTime();

            for (int sent = 0; sent < UPLOADS; sent += BATCH) {
                List<CompletableFuture<StreamOutcome>> batch = new ArrayList<>();
                for (int i = 0; i < BATCH; i++) {
                    batch.add(upload(vertx, client));
                }
                CompletableFuture.allOf(batch.toArray(new CompletableFuture<?>[0]))
                        .get(BATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                batch.forEach(settled -> outcomes.add(settled.join()));
            }
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            boolean closedDuringRun = connectionClosed.get();

            List<StreamOutcome> failed = outcomes.stream().filter(outcome -> outcome.failure() != null).toList();
            List<StreamOutcome> notAnswered = outcomes.stream().filter(outcome -> outcome.status() != 200).toList();
            long gatewayResets = outcomes.stream().filter(StreamOutcome::resetByGatewayWithNoError).count();
            long cappedUploads = outcomes.stream().filter(outcome -> outcome.sent() >= UPLOAD_CAP_BYTES).count();
            assertAll("early-answered HTTP/2 uploads over one connection",
                    () -> assertEquals(UPLOADS, outcomes.size(), "every upload must have settled"),
                    () -> assertTrue(failed.isEmpty(), () -> failed.size() + " upload(s) failed, first: "
                            + failed.getFirst().failure()),
                    () -> assertTrue(notAnswered.isEmpty(), () -> notAnswered.size()
                            + " upload(s) were not answered 200, first status: " + notAnswered.getFirst().status()),
                    () -> assertEquals(UPLOADS, gatewayResets, "every stream must end in the gateway's NO_ERROR"
                            + " reset; the client reset the rest itself with CANCEL, which is the reset the"
                            + " Rapid-Reset guard counts"),
                    () -> assertEquals(0L, cappedUploads, "every upload must have been stopped by the gateway,"
                            + " not by its own cap"),
                    () -> assertTrue(goAwayCodes.isEmpty(), () -> "the gateway sent GOAWAY " + goAwayCodes),
                    () -> assertFalse(closedDuringRun, "the connection must stay open for the whole run"),
                    () -> assertEquals(1, connections.size(), "every upload must share the one connection"),
                    () -> assertTrue(elapsed.compareTo(GUARD_WINDOW) < 0, () -> "the run took " + elapsed
                            + ", longer than the guard's window, so it no longer stands for one window"));
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(BATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * Sends one upload that keeps writing its body until its stream ends, reads the response whole, then
     * waits up to {@link #GATEWAY_RESET_GRACE_MILLIS} for the stream to be reset. A stream the gateway
     * has not reset by then is reset by this client with {@code CANCEL}, the way a Go client would.
     */
    private CompletableFuture<StreamOutcome> upload(Vertx vertx, HttpClient client) {
        CompletableFuture<StreamOutcome> outcome = new CompletableFuture<>();
        String uri = RootPaths.normalize(System.getProperty("test.http.root-path", "/")) + UPLOAD_PATH;
        int port = Integer.parseInt(System.getProperty("test.https.port", DEFAULT_TEST_PORT));
        client.request(new RequestOptions().setMethod(HttpMethod.POST).setHost("localhost").setPort(port).setURI(uri))
                .onFailure(failure -> outcome.complete(StreamOutcome.failed(failure)))
                .onSuccess(request -> {
                    watch(request.connection());
                    // Completed with the reset the gateway sent, or with null when the grace expired first.
                    CompletableFuture<Throwable> streamEnd = new CompletableFuture<>();
                    request.exceptionHandler(streamEnd::complete);
                    request.setChunked(true);
                    AtomicLong sent = new AtomicLong();
                    pump(request, streamEnd, sent);
                    request.response()
                            .compose(response -> response.body().map(body -> response.statusCode()))
                            .onFailure(failure -> outcome.complete(StreamOutcome.failed(failure)))
                            .onSuccess(status -> {
                                long grace = vertx.setTimer(GATEWAY_RESET_GRACE_MILLIS, id -> {
                                    if (streamEnd.complete(null)) {
                                        request.reset(HTTP2_CANCEL);
                                    }
                                });
                                streamEnd.thenAccept(end -> {
                                    vertx.cancelTimer(grace);
                                    outcome.complete(new StreamOutcome(status, end, null, sent.get()));
                                });
                            });
                });
        return outcome;
    }

    /**
     * Writes {@link #CHUNK} while the stream accepts it and has not ended, and resumes once it drains
     * again — so the upload has data in flight for as long as flow control lets it, up to
     * {@link #UPLOAD_CAP_BYTES}.
     */
    private static void pump(HttpClientRequest request, CompletableFuture<Throwable> streamEnd, AtomicLong sent) {
        while (!streamEnd.isDone() && sent.get() < UPLOAD_CAP_BYTES && !request.writeQueueFull()) {
            request.write(CHUNK);
            sent.addAndGet(CHUNK.length());
        }
        if (!streamEnd.isDone() && sent.get() < UPLOAD_CAP_BYTES) {
            request.drainHandler(drained -> pump(request, streamEnd, sent));
        }
    }

    /** Records every GOAWAY and the close of a connection the first time an upload is sent on it. */
    private void watch(HttpConnection connection) {
        if (connections.add(connection)) {
            connection.goAwayHandler(goAway -> goAwayCodes.add(goAway.getErrorCode()));
            connection.closeHandler(closed -> connectionClosed.set(true));
        }
    }

    /**
     * How one upload ended.
     *
     * @param status    the response status, {@code -1} when no response was received
     * @param streamEnd what ended the stream: the reset the gateway sent, or {@code null} when this
     *                  client reset it itself after the grace
     * @param failure   why the upload failed before it was answered, {@code null} when it was answered
     * @param sent      the body bytes the upload wrote before its stream ended
     */
    private record StreamOutcome(int status, Throwable streamEnd, Throwable failure, long sent) {

        static StreamOutcome failed(Throwable failure) {
            return new StreamOutcome(-1, null, failure, 0L);
        }

        boolean resetByGatewayWithNoError() {
            return streamEnd instanceof StreamResetException reset && reset.getCode() == HTTP2_NO_ERROR;
        }
    }
}
