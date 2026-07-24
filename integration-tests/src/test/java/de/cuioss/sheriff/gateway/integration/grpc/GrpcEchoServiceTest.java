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
package de.cuioss.sheriff.gateway.integration.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Isolated unit tests for {@link GrpcEchoService}. The service is exercised directly (no
 * gRPC transport) by awaiting its Mutiny {@code Uni}/{@code Multi} results, asserting the
 * unary echo, the server-streaming fan-out, and the deliberate non-OK failure.
 */
@DisplayName("GrpcEchoService")
class GrpcEchoServiceTest {

    private static final Duration AWAIT = Duration.ofSeconds(5);

    private final GrpcEchoService service = new GrpcEchoService();

    @Nested
    @DisplayName("unary")
    class Unary {

        @ParameterizedTest
        @ValueSource(strings = {"hello", "", "unicode-☃-payload", "  spaced  "})
        @DisplayName("echoes the request message unchanged at index 0")
        void echoesMessage(String message) {
            // Arrange
            var request = EchoRequest.newBuilder().setMessage(message).build();

            // Act
            var response = service.unary(request).await().atMost(AWAIT);

            // Assert
            assertEquals(message, response.getMessage());
            assertEquals(0, response.getIndex());
        }
    }

    @Nested
    @DisplayName("serverStream")
    class ServerStream {

        @Test
        @DisplayName("emits count responses, each echoing the message with a rising index")
        void emitsCountResponses() {
            // Arrange
            var request = EchoRequest.newBuilder().setMessage("tick").setCount(3).build();

            // Act
            List<EchoResponse> responses = service.serverStream(request)
                    .collect().asList().await().atMost(AWAIT);

            // Assert
            assertEquals(3, responses.size());
            for (int index = 0; index < responses.size(); index++) {
                assertEquals("tick", responses.get(index).getMessage());
                assertEquals(index, responses.get(index).getIndex());
            }
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -100})
        @DisplayName("clamps a non-positive count to a single response")
        void clampsNonPositiveCount(int count) {
            // Arrange
            var request = EchoRequest.newBuilder().setMessage("one").setCount(count).build();

            // Act
            List<EchoResponse> responses = service.serverStream(request)
                    .collect().asList().await().atMost(AWAIT);

            // Assert
            assertEquals(1, responses.size());
            assertEquals("one", responses.getFirst().getMessage());
            assertEquals(0, responses.getFirst().getIndex());
        }
    }

    @Nested
    @DisplayName("fail")
    class Fail {

        @Test
        @DisplayName("always completes with a non-OK FAILED_PRECONDITION status")
        void failsWithFailedPrecondition() {
            // Arrange
            var request = EchoRequest.newBuilder().setMessage("ignored").build();

            // Act
            var thrown = assertThrows(StatusRuntimeException.class,
                    () -> service.fail(request).await().atMost(AWAIT));

            // Assert
            assertEquals(Status.Code.FAILED_PRECONDITION, thrown.getStatus().getCode());
        }
    }
}
