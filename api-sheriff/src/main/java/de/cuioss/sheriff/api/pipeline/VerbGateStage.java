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
package de.cuioss.sheriff.api.pipeline;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayException;
import de.cuioss.sheriff.api.routing.RouteRuntime;

/**
 * Stage 2b — the per-route verb gate, run after route selection.
 * <p>
 * A request whose method is outside the selected route's effective {@code allowed_methods} is
 * rejected 405 ({@link EventType#METHOD_NOT_ALLOWED}) with the {@code Allow} response header naming
 * the permitted verbs, per RFC 7231 §6.5.5. The {@code Allow} header is seeded onto the request's
 * response-header map before the rejection is thrown so the edge renders it on the 405. The upstream
 * is never contacted for a rejected verb.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class VerbGateStage {

    /**
     * Enforces the route's effective verb allowlist.
     *
     * @param request the in-flight request context; its route must be selected (stage 2)
     * @throws GatewayException with {@link EventType#METHOD_NOT_ALLOWED} when the verb is not allowed
     */
    public void process(PipelineRequest request) {
        Objects.requireNonNull(request, "request");
        RouteRuntime route = requireSelectedRoute(request);
        Set<HttpMethod> allowed = route.getEffectiveAllowedMethods();
        if (!allowed.contains(request.method())) {
            request.responseHeaders().put("Allow", renderAllow(allowed));
            throw new GatewayException(EventType.METHOD_NOT_ALLOWED,
                    "Method " + request.method() + " not allowed for route " + route.getId());
        }
    }

    private static String renderAllow(Set<HttpMethod> allowed) {
        return allowed.stream().sorted().map(Enum::name).collect(Collectors.joining(", "));
    }

    private static RouteRuntime requireSelectedRoute(PipelineRequest request) {
        RouteRuntime route = request.selectedRoute();
        if (route == null) {
            throw new IllegalStateException("Verb gate requires the route selected at stage 2");
        }
        return route;
    }
}
