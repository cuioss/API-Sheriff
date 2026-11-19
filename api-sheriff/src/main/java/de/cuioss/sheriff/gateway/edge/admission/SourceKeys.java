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
package de.cuioss.sheriff.gateway.edge.admission;

import java.util.Objects;


import de.cuioss.http.forwarded.ForwardedHeaderResolver;
import de.cuioss.sheriff.gateway.detection.SourceKey;
import de.cuioss.sheriff.gateway.detection.SourceKeyResolver;
import de.cuioss.sheriff.gateway.edge.request.EdgeRequests;
import de.cuioss.sheriff.gateway.forward.ForwardedClientResolution;
import de.cuioss.sheriff.gateway.forward.TcpPeerGate;
import de.cuioss.tools.logging.CuiLogger;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import org.jspecify.annotations.Nullable;

/**
 * The source-key stage of the edge: resolves the {@link SourceKey} a request is tracked under, once,
 * on the event loop before admission, and stashes it on the context under {@link #SOURCE_KEY} for
 * every per-source consumer — the recon ledger's {@link SourceGate} and the threat score's
 * {@link ThreatClassifier} alike.
 * <p>
 * <strong>One derivation for every consumer.</strong> The edge builds this stage whenever either
 * {@code enumeration_detection} or {@code threat_classification} is enabled, so neither consumer depends
 * on the other being switched on, and both key a request on exactly the same source.
 * <p>
 * <strong>The source.</strong> The source is the client address a trusted forwarder reported —
 * resolved only for a peer the {@link TcpPeerGate} trusts, through a {@link ForwardedHeaderResolver}
 * configured exactly as the forward stage's — and otherwise the TCP peer. An untrusted peer's
 * {@code X-Forwarded-For} is never read. Only when that resolver names a client is the key the address
 * the gateway regenerates {@code X-Forwarded-For} from. When the headers name no client, the forward
 * stage regenerates no {@code X-Forwarded-For} at all, and the key is not taken from either chain: a
 * contested pair is not resolved through one family on its own, so the request is keyed on the
 * proxy's unresolved source or on the shared unknown source below. The trusted peer's headers are read through
 * {@link ForwardedClientResolution}, the one derivation the pending-authorization quota key shares,
 * and its three outcomes key the request three ways:
 * <ul>
 *   <li>a resolved client — on that client;</li>
 *   <li>a client-address header with a non-blank value that resolves no client that is an address
 *       literal — a malformed chain, a {@code Forwarded} chain disagreeing with the
 *       {@code X-Forwarded-For} chain, or a client named by an RFC 7239 obfuscated identifier such as
 *       {@code _hidden} — on the proxy's own unresolved
 *       source ({@link SourceKeyResolver#resolveUnresolvedForwarding}). A client can always produce
 *       such a header, so the request is keyed on a source the per-source consumers act on like any
 *       other, never on the shared {@link SourceKey#UNKNOWN_SOURCE}; and since no family can be shown
 *       to be the proxy-managed one, neither family is resolved on its own, so the client cannot choose
 *       the key either;</li>
 *   <li>no client-address header ({@code Forwarded} or {@code X-Forwarded-For}), or only blank ones —
 *       on {@link SourceKey#UNKNOWN_SOURCE}, never on the trusted
 *       peer's own address, which every such request through that peer would otherwise share; the
 *       per-source consumers count and report that shared key but never act on it. A client cannot
 *       produce this case: it cannot remove the header its trusted proxy appends.</li>
 * </ul>
 * The authority host is folded to the declared {@code match.host} set by the {@link SourceKeyResolver};
 * a malformed {@code Host} folds onto the undeclared-host key ({@link SourceKey#UNDECLARED_HOST}).
 * <p>
 * Usage:
 * <pre>{@code
 * SourceKeys sourceKeys = new SourceKeys(new SourceKeyResolver(declaredHosts), peerGate,
 *         new ForwardedHeaderResolver(resolverConfig, new SecurityEventCounter()));
 * SourceKey key = sourceKeys.resolveAndStash(ctx);
 * }</pre>
 * <p>
 * Thread-safe: every field is immutable or thread-safe; {@link #resolveAndStash} writes only the
 * request's own context.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class SourceKeys {

    private static final CuiLogger LOGGER = new CuiLogger(SourceKeys.class);

    /**
     * Holds the {@link SourceKey} {@link #resolveAndStash} resolved on the event loop, so a per-source
     * consumer that runs after the virtual-thread hop — where only the {@link RoutingContext} is in
     * scope — admits the rejection under the same source.
     */
    public static final String SOURCE_KEY = "sheriff.sourcekey";

    private final SourceKeyResolver keyResolver;
    private final TcpPeerGate peerGate;
    private final ForwardedHeaderResolver forwardedResolver;

    /**
     * Creates the stage.
     *
     * @param keyResolver       the resolver of the key a request is tracked under, holding the declared
     *                          {@code match.host} set
     * @param peerGate          the immediate-TCP-peer trust gate of the {@code forwarded} block
     * @param forwardedResolver a forwarded-header resolver configured exactly as the forward stage's, with
     *                          a security-event counter of its own so a malformed forwarding header of a
     *                          trusted proxy is not counted twice
     */
    public SourceKeys(SourceKeyResolver keyResolver, TcpPeerGate peerGate, ForwardedHeaderResolver forwardedResolver) {
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
        this.peerGate = Objects.requireNonNull(peerGate, "peerGate");
        this.forwardedResolver = Objects.requireNonNull(forwardedResolver, "forwardedResolver");
    }

    /**
     * Resolves the request's source and stashes it under {@link #SOURCE_KEY}. Called on the event loop
     * before admission.
     *
     * @param ctx the incoming request
     * @return the stashed key
     */
    public SourceKey resolveAndStash(RoutingContext ctx) {
        SourceKey key = resolve(ctx.request());
        ctx.put(SOURCE_KEY, key);
        return key;
    }

    /**
     * The key a request is tracked under, with the authority host folded to the declared set: for a
     * trusted peer the forwarded client address, the proxy's unresolved source when a client-address
     * header resolves no client, and {@link SourceKey#UNKNOWN_SOURCE} when the request carries no
     * client-address header, or only blank ones, never the trusted peer's own address; for any other peer
     * the TCP peer.
     *
     * @param raw the incoming request
     * @return the canonical source key
     */
    public SourceKey resolve(HttpServerRequest raw) {
        SocketAddress remote = raw.remoteAddress();
        String peerAddress = remote != null ? remote.hostAddress() : null;
        String host = authorityHostOf(raw);
        if (!peerGate.isTrustedPeer(peerAddress)) {
            return keyResolver.resolve(peerAddress, null, host);
        }
        return switch (ForwardedClientResolution.clientOf(forwardedResolver, name -> raw.headers().getAll(name))) {
            case ForwardedClientResolution.Resolved(String address) -> keyResolver.resolve(peerAddress, address, host);
            case ForwardedClientResolution.Unresolvable _ -> keyResolver.resolveUnresolvedForwarding(peerAddress, host);
            case ForwardedClientResolution.Absent _ -> keyResolver.resolve(null, null, host);
        };
    }

    /**
     * The authority host, or {@code null} when the request declares none or declares one that cannot
     * be parsed. A malformed {@code Host} makes the parse throw; on the event loop that must not end the
     * handler, so it is read as no host — the source then folds onto the undeclared-host key — and the
     * pipeline still answers the request as it would have without this stage.
     */
    private static @Nullable String authorityHostOf(HttpServerRequest raw) {
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        try {
            return EdgeRequests.authorityHost(raw);
        } catch (RuntimeException malformedAuthority) {
            LOGGER.debug(malformedAuthority, "Authority host unreadable for source resolution: %s",
                    malformedAuthority.getMessage());
            return null;
        }
    }
}
