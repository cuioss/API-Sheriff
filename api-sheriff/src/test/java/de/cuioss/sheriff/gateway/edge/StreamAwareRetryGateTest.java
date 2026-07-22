/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


import de.cuioss.sheriff.gateway.config.model.HttpMethod;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("StreamAwareRetryGate — stage 6 stream-aware retry gate")
class StreamAwareRetryGateTest {

    @Nested
    @DisplayName("idempotency classification (RFC 7231 §4.2.2)")
    class Idempotency {

        @ParameterizedTest
        @EnumSource(value = HttpMethod.class, names = {"GET", "HEAD", "PUT", "DELETE", "OPTIONS"})
        @DisplayName("classifies safe/idempotent verbs as idempotent")
        void idempotentVerbs(HttpMethod method) {
            assertTrue(StreamAwareRetryGate.isIdempotent(method), method + " must be idempotent");
        }

        @ParameterizedTest
        @EnumSource(value = HttpMethod.class, names = {"POST", "PATCH"})
        @DisplayName("classifies non-idempotent verbs as non-idempotent")
        void nonIdempotentVerbs(HttpMethod method) {
            assertFalse(StreamAwareRetryGate.isIdempotent(method), method + " must not be idempotent");
        }
    }

    @Nested
    @DisplayName("retry admission")
    class RetryAdmission {

        @Test
        @DisplayName("permits retry for an idempotent method with zero body bytes sent on a retry-enabled route")
        void permitsRetryWhenSafe() {
            StreamAwareRetryGate gate = new StreamAwareRetryGate(true);
            assertTrue(gate.allowsRetry(HttpMethod.GET, 0L));
        }

        @Test
        @DisplayName("never retries once any request body byte has been sent")
        void neverRetriesAfterFirstBodyByte() {
            StreamAwareRetryGate gate = new StreamAwareRetryGate(true);
            assertFalse(gate.allowsRetry(HttpMethod.PUT, 1L),
                    "a streamed request cannot be replayed once a body byte has crossed to the upstream");
        }

        @Test
        @DisplayName("never retries a non-idempotent method even with zero body bytes")
        void neverRetriesNonIdempotent() {
            StreamAwareRetryGate gate = new StreamAwareRetryGate(true);
            assertFalse(gate.allowsRetry(HttpMethod.POST, 0L));
        }

        @Test
        @DisplayName("never retries when the route disables retry")
        void neverRetriesWhenRouteDisablesRetry() {
            StreamAwareRetryGate gate = new StreamAwareRetryGate(false);
            assertFalse(gate.allowsRetry(HttpMethod.GET, 0L));
        }
    }
}
