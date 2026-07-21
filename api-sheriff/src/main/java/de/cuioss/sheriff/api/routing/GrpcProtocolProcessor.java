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
package de.cuioss.sheriff.api.routing;

import java.util.Set;


import de.cuioss.sheriff.api.config.model.HttpMethod;

/**
 * The gRPC processor. Every gRPC call is an HTTP/2 {@code POST} to a service/method path, so the
 * only verb this processor serves is {@link HttpMethod#POST}. The gRPC deltas over a plain HTTP
 * proxy — a forced-h2 upstream dial, opaque length-prefixed frame streaming, response-trailer relay
 * ({@code grpc-status} / {@code grpc-message}), and a trailers-only rejection response — are owned by
 * the edge's {@code GrpcDispatchStage} and {@code GrpcStatusMapper}; this processor carries only the
 * verb semantics.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class GrpcProtocolProcessor implements ProtocolProcessor {

    private static final Set<HttpMethod> GRPC_METHODS = Set.of(HttpMethod.POST);

    @Override
    public String id() {
        return "grpc";
    }

    @Override
    public Set<HttpMethod> standardMethods() {
        return GRPC_METHODS;
    }

    @Override
    public boolean supports(HttpMethod method) {
        return GRPC_METHODS.contains(method);
    }
}
