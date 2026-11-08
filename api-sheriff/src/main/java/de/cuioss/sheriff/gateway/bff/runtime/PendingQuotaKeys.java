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
package de.cuioss.sheriff.gateway.bff.runtime;

import java.util.Objects;


import de.cuioss.http.forwarded.ForwardedHeaderResolver;
import de.cuioss.sheriff.gateway.detection.SourceKey;
import de.cuioss.sheriff.gateway.detection.SourceKeyResolver;
import de.cuioss.sheriff.gateway.forward.TcpPeerGate;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;

/**
 * Derives the quota key a pending-authorization record is counted under — the requester's canonical
 * source — so the pending store can cap the live records per source and one source's login flood
 * displaces only that source's own records.
 * <p>
 * <strong>The source.</strong> For a peer the {@link TcpPeerGate} trusts, the client address the
 * {@link ForwardedHeaderResolver} resolves from its forwarding headers — the gate and the resolver come
 * from {@link ForwardedTrust}, so they carry the forward stage's trusted-proxy set and resolver
 * configuration — and {@link SourceKey#UNKNOWN_SOURCE} when those headers resolve no client, never the
 * trusted peer's own address, which every such request through that peer would otherwise share. For
 * any other peer,
 * the TCP peer address; its forwarding headers are never read. The address is canonicalized by
 * {@link SourceKeyResolver#canonicalSource}: IPv4 kept whole, IPv6 reduced to its {@code /64}, an absent
 * or unparseable address folded to {@link SourceKey#UNKNOWN_SOURCE}. No authority host is part of the
 * key.
 * <p>
 * <strong>The peer behind the gateway's own SNI front.</strong> With {@code tls.passthrough_sni} set,
 * every terminated connection reaches the edge over the front listener's loopback relay leg, so the
 * TCP peer would be that leg for every client. Where the edge recorded a
 * {@linkplain PipelineRequest#relayedPeerAddress() relayed peer address} — handed over in process by
 * the relay, never read from a header — that address takes the TCP peer's place in both rules above:
 * it is the peer the trust gate judges and the address an untrusted peer is keyed on. Only this key
 * makes that substitution. The forward stage keeps judging the TCP peer — the relay's loopback leg —
 * when it regenerates {@code X-Forwarded-For}, so behind the front the quota key and the regenerated
 * header can name different clients for one request; what they share is the trust, not the peer
 * (see {@link ForwardedTrust}).
 * <p>
 * <strong>One derivation, independent of detection.</strong> The key is derived here for every
 * producer of a pending record — the session stage and the reserved login, step-up and callback
 * paths — and never read from the edge-stashed source key, which exists only while
 * {@code enumeration_detection} or {@code threat_classification} is enabled.
 * <p>
 * Usage:
 * <pre>{@code
 * PendingQuotaKeys quotaKeys = new PendingQuotaKeys(new TcpPeerGate(trustedProxies),
 *         new ForwardedHeaderResolver(resolverConfig, new SecurityEventCounter()));
 * String quotaKey = quotaKeys.quotaKeyOf(request);
 * }</pre>
 * <p>
 * <strong>Thread safety.</strong> Immutable; both collaborators are safe for concurrent use. One
 * instance serves every request of a runtime.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class PendingQuotaKeys {

    private final TcpPeerGate peerGate;
    private final ForwardedHeaderResolver forwardedResolver;

    /**
     * Creates the derivation.
     *
     * @param peerGate          the immediate-TCP-peer trust gate of the {@code forwarded} block
     * @param forwardedResolver a forwarded-header resolver over the forward stage's resolver configuration,
     *                          with a security-event counter of its own so a malformed forwarding
     *                          header of a trusted proxy is not counted twice
     */
    public PendingQuotaKeys(TcpPeerGate peerGate, ForwardedHeaderResolver forwardedResolver) {
        this.peerGate = Objects.requireNonNull(peerGate, "peerGate");
        this.forwardedResolver = Objects.requireNonNull(forwardedResolver, "forwardedResolver");
    }

    /**
     * The quota key of {@code request}: see the class documentation for the rules.
     *
     * @param request the in-flight request
     * @return the canonical source text, {@link SourceKey#UNKNOWN_SOURCE} when no source resolves
     */
    public String quotaKeyOf(PipelineRequest request) {
        Objects.requireNonNull(request, "request");
        String relayedPeer = request.relayedPeerAddress();
        String peerAddress = relayedPeer != null ? relayedPeer : request.peerAddress();
        if (!peerGate.isTrustedPeer(peerAddress)) {
            return SourceKeyResolver.canonicalSource(peerAddress);
        }
        return forwardedResolver.resolve(request::headerValues).clientIp()
                .map(SourceKeyResolver::canonicalSource)
                .orElse(SourceKey.UNKNOWN_SOURCE);
    }
}
