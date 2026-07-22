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
package de.cuioss.sheriff.gateway.pipeline;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;


import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;

/**
 * D3b GW-02 anti-request-smuggling / framing gate, run at stage 1 and re-runnable after any
 * header mutation.
 * <p>
 * The gate rejects the three framing-desync vectors with a 400
 * {@link EventType#SECURITY_FILTER_VIOLATION} before a request can reach the upstream:
 * <ul>
 *   <li><strong>CL+TE</strong>: {@code Content-Length} and {@code Transfer-Encoding} both present,
 *       the classic front-end/back-end desync primer;</li>
 *   <li><strong>body on a bodyless method</strong>: a declared body (or {@code Transfer-Encoding})
 *       on {@code GET} or {@code HEAD};</li>
 *   <li><strong>framing/trust-header strip via {@code Connection}</strong>: a {@code Connection}
 *       token naming a framing header ({@code Content-Length} / {@code Transfer-Encoding} /
 *       {@code Host}) or a trust header ({@code Authorization} / {@code Forwarded} /
 *       {@code X-Forwarded-*}), which would drop that header hop-by-hop and reopen the desync.</li>
 * </ul>
 * Because the gate is stateless it is safe to re-invoke after stage 5 regenerates forwarding
 * headers, re-asserting framing integrity on the mutated header set.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class FramingGate {

    private static final Set<HttpMethod> BODYLESS_METHODS = EnumSet.of(HttpMethod.GET, HttpMethod.HEAD);

    private static final Set<String> PROTECTED_HEADERS = Set.of(
            "content-length", "transfer-encoding", "host",
            "authorization", "forwarded",
            "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-forwarded-port");

    /**
     * Re-asserts framing integrity on the current header set.
     *
     * @param request the in-flight request context
     * @throws GatewayException with {@link EventType#SECURITY_FILTER_VIOLATION} on any framing vector
     */
    public void process(PipelineRequest request) {
        Objects.requireNonNull(request, "request");
        rejectConflictingFraming(request);
        rejectBodyOnBodylessMethod(request);
        rejectFramingHeaderStrip(request);
    }

    private static void rejectConflictingFraming(PipelineRequest request) {
        // RFC 7230 §3.3.2: a message carrying more than one Content-Length field — whether sent as
        // multiple Content-Length headers or as a single field with a comma-separated value list —
        // is ambiguous and MUST be rejected, since it is a classic HTTP request-smuggling vector.
        // This is checked before the CL+TE coexistence rule below.
        List<String> contentLengths = request.headerValues("Content-Length");
        if (contentLengths.size() > 1) {
            throw violation("Multiple Content-Length headers present");
        }
        if (!contentLengths.isEmpty() && contentLengths.getFirst().indexOf(',') >= 0) {
            throw violation("Content-Length header carries a comma-separated value list");
        }
        if (request.hasHeader("Content-Length") && request.hasHeader("Transfer-Encoding")) {
            throw violation("Content-Length and Transfer-Encoding both present");
        }
    }

    private static void rejectBodyOnBodylessMethod(PipelineRequest request) {
        if (BODYLESS_METHODS.contains(request.method())
                && (request.bodyPresent() || request.declaredContentLength() > 0
                || request.hasHeader("Transfer-Encoding"))) {
            throw violation("Body present on bodyless method " + request.method());
        }
    }

    private static void rejectFramingHeaderStrip(PipelineRequest request) {
        for (String connectionValue : request.headerValues("Connection")) {
            for (String token : connectionValue.split(",")) {
                if (PROTECTED_HEADERS.contains(token.strip().toLowerCase(Locale.ROOT))) {
                    throw violation("Connection header attempts to strip protected header " + token.strip());
                }
            }
        }
    }

    private static GatewayException violation(String detail) {
        return new GatewayException(EventType.SECURITY_FILTER_VIOLATION, "Framing rejected: " + detail);
    }
}
