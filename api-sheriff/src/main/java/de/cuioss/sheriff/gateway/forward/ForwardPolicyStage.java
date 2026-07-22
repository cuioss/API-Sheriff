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
package de.cuioss.sheriff.gateway.forward;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;


import de.cuioss.http.forwarded.ForwardedHeaderResolver;
import de.cuioss.http.forwarded.ResolvedForwarding;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;

/**
 * Stage 5 — the zero-trust forward policy, run after authentication and before upstream dispatch.
 * <p>
 * The stage computes exactly what crosses to the upstream, deny-by-default:
 * <ul>
 *   <li><strong>Allowlists.</strong> Only headers named in {@code headers_allow} and query
 *       parameters named in {@code query_allow} are forwarded; everything else is dropped. Inbound
 *       {@code Authorization} crosses only when explicitly allow-listed.</li>
 *   <li><strong>Regenerated forwarding headers.</strong> Inbound {@code X-Forwarded-*} /
 *       {@code Forwarded} headers are NEVER propagated — they are regenerated through the shared
 *       {@link ForwardedHeaderResolver}, emitting {@code X-Forwarded-*} always and RFC 7239
 *       {@code Forwarded} additionally when {@code emit: both}. When the immediate TCP peer is not a
 *       {@linkplain TcpPeerGate#isTrustedPeer(String) trusted proxy}, inbound forwarding headers are
 *       ignored (a spoofed chain from an untrusted peer never influences the regenerated set).</li>
 *   <li><strong>Static set headers.</strong> {@code set_headers} are appended verbatim.</li>
 *   <li><strong>Conditional requests.</strong> {@code If-None-Match} / {@code If-Modified-Since}
 *       cross only when the route enables {@code not_modified}; otherwise they are dropped here.</li>
 * </ul>
 * The stage is framework-agnostic: the immediate peer address is supplied on the
 * {@link PipelineRequest} by the edge.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ForwardPolicyStage {

    static final String EMIT_BOTH = "both";
    private static final String FORWARDED_HEADER = "Forwarded";

    private static final Set<String> FORWARDING_HEADERS = Set.of(
            "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-forwarded-port",
            "x-forwarded-prefix", "forwarded");
    private static final List<String> CONDITIONAL_HEADERS = List.of("If-None-Match", "If-Modified-Since");

    private final ForwardedHeaderResolver resolver;
    private final TcpPeerGate peerGate;
    private final boolean emitForwarded;

    /**
     * @param resolver the shared, boot-wired forwarded-header resolver
     * @param peerGate the immediate-TCP-peer trust gate (ADR-0003)
     * @param emitMode the {@code forwarded.emit} mode ({@code x-forwarded} or {@code both})
     */
    public ForwardPolicyStage(ForwardedHeaderResolver resolver, TcpPeerGate peerGate, String emitMode) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.peerGate = Objects.requireNonNull(peerGate, "peerGate");
        this.emitForwarded = EMIT_BOTH.equals(Objects.requireNonNull(emitMode, "emitMode"));
    }

    /**
     * Computes the deny-by-default upstream header and query sets.
     *
     * @param request            the in-flight request context
     * @param forwardConfig      the selected route's {@code forward} block
     * @param notModifiedEnabled whether the route honours conditional requests
     * @return the forwarded headers and query parameters that cross to the upstream
     */
    public Result process(PipelineRequest request, ForwardConfig forwardConfig, boolean notModifiedEnabled) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(forwardConfig, "forwardConfig");

        Map<String, String> headers = new LinkedHashMap<>();
        copyAllowedHeaders(request, forwardConfig, headers);
        headers.putAll(forwardConfig.setHeaders());
        applyConditionalHeaders(request, notModifiedEnabled, headers);
        applyRegeneratedForwarding(request, headers);

        return new Result(Map.copyOf(headers), copyAllowedQuery(request, forwardConfig));
    }

    private static void copyAllowedHeaders(PipelineRequest request, ForwardConfig forwardConfig,
            Map<String, String> headers) {
        for (String name : forwardConfig.headersAllow()) {
            if (FORWARDING_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            request.firstHeader(name).ifPresent(value -> headers.put(name, value));
        }
    }

    private static void applyConditionalHeaders(PipelineRequest request, boolean notModifiedEnabled,
            Map<String, String> headers) {
        if (!notModifiedEnabled) {
            return;
        }
        for (String name : CONDITIONAL_HEADERS) {
            request.firstHeader(name).ifPresent(value -> headers.put(name, value));
        }
    }

    private void applyRegeneratedForwarding(PipelineRequest request, Map<String, String> headers) {
        boolean peerTrusted = peerGate.isTrustedPeer(request.peerAddress());
        UnaryOperator<String> lookup = name -> {
            if (!peerTrusted && FORWARDING_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return null;
            }
            return request.firstHeader(name).orElse(null);
        };
        ResolvedForwarding resolved = resolver.resolve(lookup);
        headers.putAll(resolved.toXForwardedHeaders());
        if (emitForwarded) {
            resolved.toForwardedHeader().ifPresent(value -> headers.put(FORWARDED_HEADER, value));
        }
    }

    private static Map<String, List<String>> copyAllowedQuery(PipelineRequest request, ForwardConfig forwardConfig) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        for (String name : forwardConfig.queryAllow()) {
            List<String> values = request.queryParameters().get(name);
            if (values != null && !values.isEmpty()) {
                query.put(name, List.copyOf(values));
            }
        }
        return Map.copyOf(query);
    }

    /**
     * The computed upstream request projection: the regenerated, allow-listed headers and the
     * allow-listed query parameters that cross to the upstream.
     *
     * @param headers the outbound header set (allow-listed + set_headers + regenerated forwarding)
     * @param query   the outbound query parameters (allow-listed only)
     */
    public record Result(Map<String, String> headers, Map<String, List<String>> query) {

        /**
         * Canonical constructor defensively copying the collections.
         */
        public Result {
            headers = Map.copyOf(headers);
            query = Map.copyOf(query);
        }
    }
}
