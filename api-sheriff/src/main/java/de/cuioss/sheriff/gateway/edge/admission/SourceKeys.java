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
 * configured exactly as the forward stage's, so the address keyed on is the one the gateway regenerates
 * {@code X-Forwarded-For} from — and otherwise the TCP peer. An untrusted peer's
 * {@code X-Forwarded-For} is never read. A trusted peer's request whose forwarding headers resolve to no
 * client is keyed on {@link SourceKey#UNKNOWN_SOURCE}, never on the trusted peer's own address, which
 * every such request through that peer would otherwise share. The authority host is folded to the
 * declared {@code match.host} set by the {@link SourceKeyResolver}; a malformed {@code Host} folds onto
 * the undeclared-host key ({@link SourceKey#UNDECLARED_HOST}).
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
     * trusted peer the forwarded client address, and {@link SourceKey#UNKNOWN_SOURCE} when its
     * forwarding headers resolve to no client — never the trusted peer's own address; for any other peer
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
        return forwardedResolver.resolve(name -> raw.headers().getAll(name)).clientIp()
                .map(client -> keyResolver.resolve(peerAddress, client, host))
                .orElseGet(() -> keyResolver.resolve(null, null, host));
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
