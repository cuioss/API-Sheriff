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
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;


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
 *   <li><strong>The gateway-understood protocol set.</strong> The {@link #PROTOCOL_HEADERS} names
 *       cross on their own, so an operator never has to enumerate HTTP mechanics to make a backend
 *       work. Under forward-all the set is redundant; under a positive-list it is where it earns its
 *       keep. A {@code headers_deny} entry naming one of them still wins — the set is applied only
 *       to names the mode copy did not already refuse.</li>
 *   <li><strong>Regenerated forwarding headers.</strong> Inbound {@code X-Forwarded-*} /
 *       {@code Forwarded} headers are NEVER propagated — they are regenerated through the shared
 *       {@link ForwardedHeaderResolver}, emitting {@code X-Forwarded-*} always and RFC 7239
 *       {@code Forwarded} additionally when {@code emit: both}. When the immediate TCP peer is not a
 *       {@linkplain TcpPeerGate#isTrustedPeer(String) trusted proxy}, inbound forwarding headers are
 *       ignored (a spoofed chain from an untrusted peer never influences the regenerated set). The
 *       client copy skips this set on <em>every</em> mode, forward-all included — a regenerated
 *       header is never copied from the client.</li>
 *   <li><strong>Static set headers.</strong> {@code set_headers} are merged after the client copy
 *       and the protocol set, so an operator's explicit value is never silently overridden by the
 *       inbound request.</li>
 *   <li><strong>Mediated session bearer.</strong> A {@code require: session} route's stage-4
 *       runtime records the mediated access token on the request; it is rendered here as the
 *       outbound {@code Authorization: Bearer} <em>last</em>, so it wins over any inbound
 *       {@code Authorization} a mode happened to forward. The upstream therefore sees only the
 *       mediated bearer.</li>
 *   <li><strong>Conditional requests.</strong> The five {@link #CONDITIONAL_HEADERS} validators are
 *       the protocol set's gated tier: they cross only when the route enables {@code not_modified},
 *       which is the same flag the response direction reads before relaying {@code ETag} /
 *       {@code Last-Modified}. Gating both halves on one flag is what stops the gateway asking a
 *       question whose answer it then discards.</li>
 * </ul>
 * <p>
 * <strong>The application order is pinned, and it encodes a precedence rule.</strong>
 * {@link #process} runs: the mode copy and the never-forward strip, then the protocol set, then
 * {@code set_headers}, then the regenerated forwarding headers, then the mediated bearer. Two
 * sentences explain every step of it — <em>operator configuration beats client input</em>, so
 * {@code set_headers} is merged after everything sourced from the request; and <em>gateway-owned
 * transport truth beats operator configuration</em>, so regeneration and the mediated bearer are
 * merged after {@code set_headers}, with the mediated bearer last.
 * <p>
 * The outbound header map is keyed case-insensitively, matching RFC 9110 field-name semantics: a
 * later stage replaces an earlier stage's value for the same field however either spelled it. Without
 * that, an inbound lower-cased {@code content-type} and an operator's {@code Content-Type} would both
 * reach the upstream and the precedence rule above would not hold.
 * <p>
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

    /**
     * Whether {@code name} is a spelling of a header the gateway <em>regenerates</em> — tested on
     * the {@linkplain ConnectionHeaders#separatorFolded separator-folded} name, so the client copy
     * skips {@code X-Forwarded_For} and {@code X_Forwarded-For} exactly as it skips the canonical
     * {@code X-Forwarded-For}.
     * <p>
     * The folding is what closes the {@code -}/{@code _} equivalence class here rather than
     * enumerating its members: a CGI-folding backend resolves all four spellings of a two-separator
     * name to one variable, so a mixed spelling that crossed would let a client's claim land on the
     * same variable as the gateway's regenerated one. {@link ConnectionHeaders#REQUEST_STRIP} closes
     * the same class for the names the gateway does <em>not</em> regenerate; between the two, every
     * spelling of every provenance name reaches one of the two sets.
     */
    private static boolean isRegeneratedForwardingName(String name) {
        return FORWARDING_HEADERS.contains(ConnectionHeaders.separatorFolded(name));
    }

    /**
     * The protocol-set tier that crosses whatever the route configures — content negotiation and
     * range requests, the mechanics every backend speaks and no operator should have to enumerate.
     */
    private static final List<String> UNCONDITIONAL_PROTOCOL_HEADERS = List.of(
            "Accept", "Accept-Encoding", "Accept-Language", "Content-Type", "Range");

    /**
     * The protocol-set tier gated by {@code not_modified} — the five conditional-request validators
     * RFC 9110 §13 defines. They ride the toggle because their answers ({@code ETag} /
     * {@code Last-Modified}, and a {@code 304} or {@code 206}) are gated by the same flag in the
     * response direction: forwarding a validator whose response counterpart is then stripped would
     * make the gateway ask a question it discards the answer to.
     */
    private static final List<String> CONDITIONAL_HEADERS = List.of(
            "If-None-Match", "If-Modified-Since", "If-Match", "If-Unmodified-Since", "If-Range");

    /**
     * The gateway-understood protocol headers, both tiers together — the names that cross without an
     * operator entry.
     * <p>
     * Membership answers <em>"does the gateway know what this header means?"</em>, not "is it
     * technically necessary". The latter rule would admit {@code Cookie} and {@code Authorization},
     * which is precisely what {@link ConnectionHeaders#REQUEST_STRIP} withholds. {@code User-Agent}
     * is deliberately out on the same test: widely expected, required for nothing, and a backend that
     * genuinely needs it can name it.
     * <p>
     * The set is disjoint from {@link ConnectionHeaders#REQUEST_STRIP} by construction, which is why
     * applying it after the strip re-admits nothing the gateway owns. It is a fixed constant with no
     * configuration surface: an operator who wants one of these withheld denies it by name.
     */
    static final List<String> PROTOCOL_HEADERS = Stream.concat(
            UNCONDITIONAL_PROTOCOL_HEADERS.stream(), CONDITIONAL_HEADERS.stream()).toList();

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
     * the route's per-dimension forward mode in the pinned order documented on the class.
     *
     * @param request            the in-flight request context
     * @param forwardConfig      the selected route's {@code forward} block
     * @param notModifiedEnabled whether the route honours conditional requests
     * @return the forwarded headers and query parameters that cross to the upstream
     */
    public Result process(PipelineRequest request, ForwardConfig forwardConfig, boolean notModifiedEnabled) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(forwardConfig, "forwardConfig");

        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        copyHeadersByMode(request, forwardConfig, headers);
        applyRequestStrip(forwardConfig, headers);
        applyProtocolHeaders(request, forwardConfig, notModifiedEnabled, headers);
        headers.putAll(forwardConfig.setHeaders());
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
     * The {@link #FORWARDING_HEADERS} skip is applied on every mode, and on the
     * {@linkplain #isRegeneratedForwardingName separator-folded} name: a regenerated header must
     * never be copied from the client under any spelling, so neither forward-all nor an
     * {@code X-Forwarded_For} is a way to smuggle one in.
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
            if (isRegeneratedForwardingName(name)) {
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
            if (values.isEmpty() || isRegeneratedForwardingName(name) || denied.contains(name)) {
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
        if (AUTHORIZATION.equalsIgnoreCase(name)) {
            return !authorizationReadmitted;
        }
        if (TE.equalsIgnoreCase(name)) {
            return !TRAILERS.equalsIgnoreCase(value.strip());
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

    /**
     * Admits the {@link #PROTOCOL_HEADERS} the client actually sent, so a route never has to
     * enumerate HTTP mechanics to work. The unconditional tier always applies; the
     * {@link #CONDITIONAL_HEADERS} tier applies only when the route enables {@code not_modified}.
     * <p>
     * <strong>A negative-list entry beats the set.</strong> The set is applied only to names the mode
     * copy did not already refuse, so a route that denies {@code Content-Type} by name gets it
     * withheld rather than re-admitted here. A <em>positive</em>-list is deliberately not consulted:
     * crossing without being named is the whole point of the set, and a route that wants one of these
     * withheld says so with a deny entry.
     * <p>
     * The names are read from the request rather than from the already-copied map because a
     * positive-list route never copied them in the first place. That bypasses
     * {@link #applyRequestStrip}, which is safe only because the two sets are disjoint — see
     * {@link #PROTOCOL_HEADERS}.
     */
    private static void applyProtocolHeaders(PipelineRequest request, ForwardConfig forwardConfig,
            boolean notModifiedEnabled, Map<String, String> headers) {
        Set<String> denied = lowerCased(forwardConfig.headersDeny());
        admitProtocolTier(request, denied, UNCONDITIONAL_PROTOCOL_HEADERS, headers);
        if (notModifiedEnabled) {
            admitProtocolTier(request, denied, CONDITIONAL_HEADERS, headers);
        }
    }

    private static void admitProtocolTier(PipelineRequest request, Set<String> denied, List<String> tier,
            Map<String, String> headers) {
        for (String name : tier) {
            if (denied.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            request.firstHeader(name).ifPresent(value -> headers.put(name, value));
        }
    }

    private void applyRegeneratedForwarding(PipelineRequest request, Map<String, String> headers) {
        boolean peerTrusted = peerGate.isTrustedPeer(request.peerAddress());
        UnaryOperator<String> lookup = name -> {
            if (!peerTrusted && isRegeneratedForwardingName(name)) {
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
     * @param headers the outbound header set, merged in the stage's pinned order (mode-filtered
     *                client headers, the gateway-understood protocol set, {@code set_headers}, the
     *                regenerated forwarding headers, and the mediated bearer)
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
