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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;


import de.cuioss.http.forwarded.ForwardedHeaderResolver;
import de.cuioss.http.forwarded.ResolvedForwarding;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.http.ConnectionHeaders;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;

import org.jspecify.annotations.Nullable;

/**
 * Stage 5 — the zero-trust forward policy, run after authentication and before upstream dispatch.
 * <p>
 * The stage computes exactly what crosses to the upstream <em>within a route the request is already
 * authorised for</em>. Deny-by-default lives at the URL layer, not here: a request matching no route
 * reaches no upstream at all.
 * <ul>
 *   <li><strong>Three forward modes, per dimension.</strong> Headers and query parameters each
 *       resolve one of three postures independently: a <em>positive-list</em> when
 *       {@code headers_allow} / {@code query_allow} is declared (only the named entries cross), a
 *       <em>negative-list</em> when {@code headers_deny} / {@code query_deny} is declared
 *       (everything crosses except the named entries), and <em>forward-all</em> when neither is
 *       declared. A declared-empty list is not the same as an absent one: {@code query_allow: []}
 *       is a positive-list admitting nothing, while an absent {@code query_allow} is forward-all.
 *       Declaring both lists for one dimension is refused at boot.</li>
 *   <li><strong>The gateway-owned never-forward set.</strong> Whatever mode a route resolves, the
 *       {@link ConnectionHeaders#REQUEST_STRIP} names are withheld from the upstream — this is what
 *       makes the forward-all baseline safe. {@code Authorization} is the single re-admittable
 *       member and only under a positive-list naming it; {@code TE} crosses only as the
 *       {@code trailers} token.</li>
 *   <li><strong>Regenerated forwarding headers.</strong> Inbound {@code X-Forwarded-*} /
 *       {@code Forwarded} headers are NEVER propagated — they are regenerated through the shared
 *       {@link ForwardedHeaderResolver}, emitting {@code X-Forwarded-*} always and RFC 7239
 *       {@code Forwarded} additionally when {@code emit: both}. When the immediate TCP peer is not a
 *       {@linkplain TcpPeerGate#isTrustedPeer(String) trusted proxy}, inbound forwarding headers are
 *       ignored (a spoofed chain from an untrusted peer never influences the regenerated set). The
 *       client copy skips this set on <em>every</em> mode, forward-all included — a regenerated
 *       header is never copied from the client.</li>
 *   <li><strong>Static set headers.</strong> {@code set_headers} are appended verbatim.</li>
 *   <li><strong>Mediated session bearer.</strong> A {@code require: session} route's stage-4
 *       runtime records the mediated access token on the request; it is rendered here as the
 *       outbound {@code Authorization: Bearer} <em>last</em>, so it wins over any inbound
 *       {@code Authorization} a mode happened to forward. The upstream therefore sees only the
 *       mediated bearer.</li>
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
    private static final String AUTHORIZATION = "authorization";
    private static final String TE = "te";
    /** The one {@code TE} value RFC 9110 admits end-to-end; every other value is hop-scoped. */
    private static final String TRAILERS = "trailers";

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
     * Computes the upstream header and query projection for an already-authorised route, applying
     * the route's per-dimension forward mode.
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
        copyHeadersByMode(request, forwardConfig, headers);
        applyRequestStrip(forwardConfig, headers);
        headers.putAll(forwardConfig.setHeaders());
        applyConditionalHeaders(request, notModifiedEnabled, headers);
        applyRegeneratedForwarding(request, headers);
        applyMediatedBearer(request, headers);

        return new Result(Map.copyOf(headers), copyQueryByMode(request, forwardConfig));
    }

    /**
     * Applies the {@code require: session} stage-4 mediated bearer as the outbound
     * {@code Authorization} header, last, so it deterministically wins over any inbound
     * {@code Authorization} an allowlist happened to forward. A pure-proxy or {@code require: bearer}
     * route resolves no mediated bearer, so this is a no-op there.
     */
    private static void applyMediatedBearer(PipelineRequest request, Map<String, String> headers) {
        request.mediatedBearer().ifPresent(token -> headers.put("Authorization", "Bearer " + token));
    }

    /**
     * Copies the client request headers according to the route's header forward mode: a declared
     * {@code headers_allow} selects the positive-list, otherwise the copy iterates the inbound
     * headers and withholds the {@code headers_deny} names (an absent deny list denying nothing —
     * the forward-all baseline).
     * <p>
     * The {@link #FORWARDING_HEADERS} skip is applied on every mode: a regenerated header must never
     * be copied from the client, so forward-all is not a way to smuggle one in.
     */
    private static void copyHeadersByMode(PipelineRequest request, ForwardConfig forwardConfig,
            Map<String, String> headers) {
        List<String> allow = forwardConfig.headersAllow();
        if (allow != null) {
            copyPositiveListHeaders(request, allow, headers);
            return;
        }
        copyInboundHeadersExcept(request, lowerCased(forwardConfig.headersDeny()), headers);
    }

    private static void copyPositiveListHeaders(PipelineRequest request, List<String> allow,
            Map<String, String> headers) {
        for (String name : allow) {
            if (FORWARDING_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            request.firstHeader(name).ifPresent(value -> headers.put(name, value));
        }
    }

    /**
     * The negative-list and forward-all copy — one implementation, because forward-all is exactly a
     * negative-list denying nothing. The inbound names arrive already lower-cased by
     * {@link PipelineRequest}, so the denied names are lower-cased once here and membership is the
     * case-insensitive comparison RFC 9110 field names require.
     */
    private static void copyInboundHeadersExcept(PipelineRequest request, Set<String> denied,
            Map<String, String> headers) {
        for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
            String name = entry.getKey();
            List<String> values = entry.getValue();
            if (values.isEmpty() || FORWARDING_HEADERS.contains(name) || denied.contains(name)) {
                continue;
            }
            headers.put(name, values.getFirst());
        }
    }

    /**
     * Withholds the gateway-owned {@link ConnectionHeaders#REQUEST_STRIP} names from the copied
     * client headers. Applied on <em>every</em> mode, after the mode copy: no forward mode — not
     * even a {@code headers_allow} naming one — re-admits these, with the two carve-outs below.
     * <p>
     * The strip runs before {@code set_headers} is merged, so an operator's explicit static header
     * is never removed by it: this gate governs <em>client input</em>, and operator configuration is
     * not client input.
     * <p>
     * Two members carry behaviour beyond membership, which is why they are decided here rather than
     * in the set literal:
     * <ul>
     *   <li><strong>{@code authorization} is the single re-admittable member</strong>, and only when
     *       the route declares a positive-list naming it. Under a negative-list or forward-all the
     *       route declares no {@code headers_allow}, so it is never re-admitted there — a permissive
     *       baseline must not leak an inbound credential. A re-admitted value is still overwritten
     *       by {@link #applyMediatedBearer}, which runs last.</li>
     *   <li><strong>{@code te} crosses only when its value is exactly the {@code trailers} token.</strong>
     *       RFC 9110 admits that one value; any other ({@code gzip}, or {@code trailers} alongside
     *       another transfer coding) is a transfer-coding negotiation for the hop the gateway
     *       terminates and is withheld.</li>
     * </ul>
     */
    private static void applyRequestStrip(ForwardConfig forwardConfig, Map<String, String> headers) {
        boolean authorizationReadmitted = namesAuthorization(forwardConfig.headersAllow());
        headers.entrySet().removeIf(entry -> isWithheldFromUpstream(entry.getKey(), entry.getValue(),
                authorizationReadmitted));
    }

    private static boolean isWithheldFromUpstream(String name, String value, boolean authorizationReadmitted) {
        if (!ConnectionHeaders.isRequestStripped(name)) {
            return false;
        }
        String lowerName = name.toLowerCase(Locale.ROOT);
        if (AUTHORIZATION.equals(lowerName)) {
            return !authorizationReadmitted;
        }
        if (TE.equals(lowerName)) {
            return !TRAILERS.equals(value.strip().toLowerCase(Locale.ROOT));
        }
        return true;
    }

    /**
     * Whether the route's positive-list names {@code Authorization}. A {@code null} list means the
     * route declares no positive-list at all (negative-list or forward-all), where the re-admission
     * never applies.
     */
    private static boolean namesAuthorization(@Nullable List<String> headersAllow) {
        if (headersAllow == null) {
            return false;
        }
        for (String name : headersAllow) {
            if (AUTHORIZATION.equals(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The declared names lower-cased for case-insensitive header membership, or the empty set when
     * the list is absent — the forward-all baseline, which denies nothing.
     */
    private static Set<String> lowerCased(@Nullable List<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        Set<String> lowered = new LinkedHashSet<>();
        for (String name : names) {
            lowered.add(name.toLowerCase(Locale.ROOT));
        }
        return lowered;
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

    /**
     * Copies the client query parameters according to the route's query forward mode: a declared
     * {@code query_allow} selects the positive-list, otherwise every inbound parameter crosses
     * except the {@code query_deny} names (an absent deny list denying nothing — the forward-all
     * baseline).
     * <p>
     * Parameter-name matching is <strong>case-sensitive</strong> on both modes, unlike the header
     * copy. Query-parameter names are case-sensitive in HTTP and the positive-list has always
     * matched them exactly; making only the deny side case-insensitive would let a route's two
     * lists disagree about what a name is.
     */
    private static Map<String, List<String>> copyQueryByMode(PipelineRequest request, ForwardConfig forwardConfig) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        List<String> allow = forwardConfig.queryAllow();
        if (allow != null) {
            for (String name : allow) {
                List<String> values = request.queryParameters().get(name);
                if (values != null && !values.isEmpty()) {
                    query.put(name, List.copyOf(values));
                }
            }
            return Map.copyOf(query);
        }
        List<String> deny = forwardConfig.queryDeny();
        Set<String> denied = deny == null ? Set.of() : Set.copyOf(deny);
        for (Map.Entry<String, List<String>> entry : request.queryParameters().entrySet()) {
            List<String> values = entry.getValue();
            if (values.isEmpty() || denied.contains(entry.getKey())) {
                continue;
            }
            query.put(entry.getKey(), List.copyOf(values));
        }
        return Map.copyOf(query);
    }

    /**
     * The computed upstream request projection: the mode-filtered, regenerated headers and the
     * mode-filtered query parameters that cross to the upstream.
     *
     * @param headers the outbound header set (mode-filtered client headers + set_headers +
     *                regenerated forwarding + the mediated bearer)
     * @param query   the outbound query parameters (mode-filtered client parameters only)
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
