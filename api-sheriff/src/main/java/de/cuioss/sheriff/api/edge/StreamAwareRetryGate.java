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

import java.util.Objects;
import java.util.Set;

import de.cuioss.sheriff.api.config.model.HttpMethod;

/**
 * The stream-aware retry gate for stage 6 upstream dispatch.
 * <p>
 * A streamed request cannot be safely retried once any request body byte has crossed to the
 * upstream — the source stream is consumed and cannot be replayed, and re-sending a partially
 * consumed non-idempotent request risks duplicate side effects. This gate therefore permits a
 * retry only when ALL of the following hold:
 * <ul>
 *   <li>the route enables upstream retry ({@link de.cuioss.sheriff.api.routing.RouteRuntime#isRetryEnabled()});</li>
 *   <li>the request method is idempotent (RFC 7231 §4.2.2: {@code GET}, {@code HEAD},
 *       {@code PUT}, {@code DELETE}, {@code OPTIONS} — never {@code POST} / {@code PATCH});</li>
 *   <li>zero request body bytes have been sent to the upstream yet.</li>
 * </ul>
 * The gate holds no mutable state; the per-request body-byte count is supplied by the caller.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class StreamAwareRetryGate {

    /** RFC 7231 §4.2.2 idempotent methods — the only verbs a partially-attempted request may retry. */
    private static final Set<HttpMethod> IDEMPOTENT_METHODS = Set.of(
            HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.OPTIONS);

    private final boolean routeRetryEnabled;

    /**
     * @param routeRetryEnabled the route's materialized upstream-retry toggle
     */
    public StreamAwareRetryGate(boolean routeRetryEnabled) {
        this.routeRetryEnabled = routeRetryEnabled;
    }

    /**
     * @param method        the inbound request method
     * @param bodyBytesSent the number of request body bytes already streamed to the upstream
     * @return {@code true} only when retry is enabled, the method is idempotent, and no body byte
     *         has yet been sent
     */
    public boolean allowsRetry(HttpMethod method, long bodyBytesSent) {
        Objects.requireNonNull(method, "method");
        return routeRetryEnabled && isIdempotent(method) && bodyBytesSent == 0L;
    }

    /**
     * @param method the request method
     * @return {@code true} when {@code method} is an idempotent verb (RFC 7231 §4.2.2)
     */
    public static boolean isIdempotent(HttpMethod method) {
        return IDEMPOTENT_METHODS.contains(Objects.requireNonNull(method, "method"));
    }
}
