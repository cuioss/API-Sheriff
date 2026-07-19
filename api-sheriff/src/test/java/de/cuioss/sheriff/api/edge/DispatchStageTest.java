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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;


import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.config.model.ResolvedUpstream;
import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayEventCounter;
import de.cuioss.sheriff.api.events.GatewayException;

import io.smallrye.faulttolerance.api.Guard;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.streams.ReadStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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

            // Assert — the breaching chunk is not forwarded, the call is aborted, and a 400 is raised
            assertEquals(1, forwarded.size(), "the breaching chunk must never cross to the upstream");
            assertTrue(aborted.get(), "a mid-stream breach must abort the in-flight upstream call");
            Throwable raised = failure.get();
            GatewayException gatewayException = assertInstanceOf(GatewayException.class, raised);
            assertEquals(EventType.PARAMETER_LIMIT_EXCEEDED, gatewayException.getEventType());
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
                throw new IllegalStateException("upstream down");
            };

            // Act
            GatewayException raised = assertThrows(GatewayException.class,
                    () -> stage.guardedDispatch(retryGuard(), gate, HttpMethod.GET, bytesSent::get, failing));

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
                throw new IllegalStateException("upstream down");
            };

            // Act
            assertThrows(GatewayException.class,
                    () -> stage.guardedDispatch(retryGuard(), gate, HttpMethod.POST, bytesSent::get, failing));

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
                throw new IllegalStateException("upstream down mid-body");
            };

            // Act
            assertThrows(GatewayException.class,
                    () -> stage.guardedDispatch(retryGuard(), gate, HttpMethod.PUT, bytesSent::get, failing));

            // Assert — a streamed request cannot be replayed once a body byte has been sent
            assertEquals(1, attempts.get(),
                    "an idempotent request whose body has streamed a byte must never be retried");
        }
    }

    /**
     * A minimal {@link ReadStream} fake: captures the handler the decorator installs and lets a test
     * push buffers synchronously through it.
     */
    private static final class TestReadStream implements ReadStream<Buffer> {

        private @Nullable Handler<Buffer> handler;
        private @Nullable Handler<Throwable> exceptionHandler;
        private @Nullable Handler<Void> endHandler;

        void emit(Buffer buffer) {
            if (handler != null) {
                handler.handle(buffer);
            }
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
