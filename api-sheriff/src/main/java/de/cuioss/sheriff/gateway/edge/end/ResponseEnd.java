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
package de.cuioss.sheriff.gateway.edge.end;

import java.util.function.Supplier;

import io.vertx.core.Future;

/**
 * The one way a client response is ended: a writer that has composed a response hands the call that
 * ends it to the edge instead of making that call itself.
 * <p>
 * The edge decides <em>when</em> the call runs. Usually that is at once. An HTTP/2 response answered
 * while its request body is still open is ended only once request data stops arriving or a time cap
 * passes, and its stream is then reset (see {@code ResponseEnds.endResponse}); a writer cannot tell
 * the two apart and does not need to. {@link de.cuioss.sheriff.gateway.edge.relay.ResponseStage} and
 * {@link de.cuioss.sheriff.gateway.edge.relay.GrpcStatusMapper} end every response
 * they write through this seam, so the relay and the trailers-only gRPC status pass the same rule as
 * every answer the edge writes itself.
 * <p>
 * Usage — a writer ending a response it has composed:
 * {@snippet :
 * client.putTrailer("grpc-status", "0");
 * return end.end(client::end);
 * }
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@FunctionalInterface
public interface ResponseEnd {

    /**
     * Ends the response through {@code ending}, now or later.
     *
     * @param ending the call that ends the response, run exactly once unless the response was ended by
     *               then; it returns the future of that end
     * @return a future completing once the response has been ended and written — and, where the edge
     *         resets the stream after it, once that reset was decided — or failing when the end failed
     */
    Future<Void> end(Supplier<Future<Void>> ending);
}
