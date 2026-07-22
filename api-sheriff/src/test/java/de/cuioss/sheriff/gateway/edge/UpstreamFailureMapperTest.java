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
package de.cuioss.sheriff.gateway.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.io.Serial;


import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayEventCounter;
import de.cuioss.sheriff.gateway.events.GatewayException;

import io.smallrye.faulttolerance.api.CircuitBreakerState;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("UpstreamFailureMapper — stage 6/7 failure mapping to the error contract")
class UpstreamFailureMapperTest {

    private final GatewayEventCounter eventCounter = new GatewayEventCounter();
    private final UpstreamFailureMapper mapper = new UpstreamFailureMapper(eventCounter);

    @Nested
    @DisplayName("failure classification")
    class Classification {

        @Test
        @DisplayName("maps a breaker-open failure to 503 UPSTREAM_CIRCUIT_OPEN")
        void breakerOpen() {
            assertEquals(EventType.UPSTREAM_CIRCUIT_OPEN,
                    mapper.classify(new CircuitBreakerOpenException("open")));
        }

        @Test
        @DisplayName("maps a MicroProfile timeout to 504 UPSTREAM_TIMEOUT")
        void microProfileTimeout() {
            assertEquals(EventType.UPSTREAM_TIMEOUT,
                    mapper.classify(new TimeoutException("t")));
        }

        @Test
        @DisplayName("maps a java.util.concurrent timeout to 504 UPSTREAM_TIMEOUT")
        void concurrentTimeout() {
            assertEquals(EventType.UPSTREAM_TIMEOUT,
                    mapper.classify(new java.util.concurrent.TimeoutException("t")));
        }

        @Test
        @DisplayName("maps any Timeout-named failure to 504 UPSTREAM_TIMEOUT")
        void timeoutNamedFailure() {
            assertEquals(EventType.UPSTREAM_TIMEOUT, mapper.classify(new ConnectionTimeoutError()));
        }

        @Test
        @DisplayName("unwraps a wrapped breaker-open cause")
        void wrappedBreakerOpen() {
            Throwable wrapped = new IllegalStateException(new CircuitBreakerOpenException("open"));
            assertEquals(EventType.UPSTREAM_CIRCUIT_OPEN, mapper.classify(wrapped));
        }

        @Test
        @DisplayName("maps a generic connection failure to 502 UPSTREAM_ERROR")
        void genericFailure() {
            assertEquals(EventType.UPSTREAM_ERROR, mapper.classify(new IOException("connection refused")));
        }

        @Test
        @DisplayName("terminates on a self-referential cause cycle")
        void selfReferentialCause() {
            SelfCausingException cyclic = new SelfCausingException();
            assertEquals(EventType.UPSTREAM_ERROR, mapper.classify(cyclic));
        }
    }

    @Nested
    @DisplayName("metering and exception production")
    class MeteringAndException {

        @Test
        @DisplayName("meters and wraps a failure into the typed GatewayException")
        void toGatewayExceptionMetersAndWraps() {
            IOException cause = new IOException("boom");

            GatewayException produced = mapper.toGatewayException(cause);

            assertEquals(EventType.UPSTREAM_ERROR, produced.getEventType());
            assertSame(cause, produced.getCause(), "the internal cause must be preserved for logging");
            assertEquals(1L, eventCounter.getCount(EventType.UPSTREAM_ERROR),
                    "every mapped failure is metered");
        }

        @Test
        @DisplayName("meters a breaker OPEN transition")
        void recordsBreakerOpen() {
            mapper.recordBreakerTransition("orders", CircuitBreakerState.OPEN);
            assertEquals(1L, eventCounter.getCount(EventType.UPSTREAM_CIRCUIT_OPEN));
        }

        @Test
        @DisplayName("does not meter a breaker CLOSED / HALF_OPEN transition")
        void ignoresNonOpenTransitions() {
            mapper.recordBreakerTransition("orders", CircuitBreakerState.CLOSED);
            mapper.recordBreakerTransition("orders", CircuitBreakerState.HALF_OPEN);
            assertEquals(0L, eventCounter.getCount(EventType.UPSTREAM_CIRCUIT_OPEN));
        }
    }

    /** A failure whose simple class name contains {@code Timeout}, matching the name-based fallback. */
    private static final class ConnectionTimeoutError extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    /** A failure that returns itself as its own cause, exercising the cause-cycle guard. */
    private static final class SelfCausingException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }
}
