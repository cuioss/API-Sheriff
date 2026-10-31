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
package de.cuioss.sheriff.gateway.edge.relay;

/**
 * Test-only bridge to the package-private {@link ResponseStage.GrpcStreamIdleBound#timeoutMillis()}, so a
 * test outside this package can assert the bound the gRPC dispatch builds without the production accessor
 * being public.
 * <p>
 * Stateless, hence thread-safe.
 *
 * @since 1.0
 */
public final class GrpcStreamIdleBounds {

    private GrpcStreamIdleBounds() {
        // static members only
    }

    /**
     * @param bound the stream-idle bound to read
     * @return the timeout {@code bound} ends a call after, in milliseconds
     */
    public static long timeoutOf(ResponseStage.GrpcStreamIdleBound bound) {
        return bound.timeoutMillis();
    }
}
