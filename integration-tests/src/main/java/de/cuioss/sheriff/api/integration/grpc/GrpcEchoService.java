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
package de.cuioss.sheriff.api.integration.grpc;

import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

/**
 * Mutiny-based Quarkus gRPC echo upstream backing the gRPC integration-test matrix.
 * It implements the three methods declared in {@code echo.proto}:
 * <ul>
 *   <li>{@link #unary(EchoRequest)} — returns the request message unchanged;</li>
 *   <li>{@link #serverStream(EchoRequest)} — emits {@code count} echoed responses;</li>
 *   <li>{@link #fail(EchoRequest)} — always completes with a non-OK {@code grpc-status}
 *       so the gateway's trailer/status relay can be asserted end-to-end.</li>
 * </ul>
 *
 * <p>This is test scaffolding: the gateway dials it over the compose network as the
 * {@code grpc-echo} upstream. The bean is stateless and therefore thread-safe; Quarkus
 * gRPC invokes the reactive methods on its event loop.</p>
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@GrpcService
public class GrpcEchoService implements Echo {

    /**
     * Echoes the request message back as a single response at index {@code 0}.
     *
     * @param request the caller's echo request
     * @return a {@link Uni} emitting the echoed response
     */
    @Override
    public Uni<EchoResponse> unary(EchoRequest request) {
        return Uni.createFrom().item(response(request.getMessage(), 0));
    }

    /**
     * Emits {@code count} responses (clamped to at least one), each echoing the request
     * message and carrying its zero-based position.
     *
     * @param request the caller's echo request; {@code count} controls the stream length
     * @return a {@link Multi} emitting the echoed responses in order
     */
    @Override
    public Multi<EchoResponse> serverStream(EchoRequest request) {
        int count = Math.max(1, request.getCount());
        return Multi.createFrom().range(0, count)
                .map(index -> response(request.getMessage(), index));
    }

    /**
     * Always fails with {@link Status#FAILED_PRECONDITION} so integration tests can assert
     * that the gateway relays a non-OK {@code grpc-status} and its trailers.
     *
     * @param request the caller's echo request (ignored)
     * @return a {@link Uni} that always fails with a non-OK status
     */
    @Override
    public Uni<EchoResponse> fail(EchoRequest request) {
        return Uni.createFrom().failure(Status.FAILED_PRECONDITION
                .withDescription("grpc-echo: intentional failure for trailer/status assertions")
                .asRuntimeException());
    }

    private static EchoResponse response(String message, int index) {
        return EchoResponse.newBuilder()
                .setMessage(message)
                .setIndex(index)
                .build();
    }
}
