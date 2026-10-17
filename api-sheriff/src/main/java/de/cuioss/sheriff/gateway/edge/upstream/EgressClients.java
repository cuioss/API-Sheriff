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
package de.cuioss.sheriff.gateway.edge.upstream;

import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.config.model.EgressTlsConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.edge.assembly.UpstreamTarget;
import de.cuioss.sheriff.gateway.tls.EgressTrustProfileResolver;
import de.cuioss.tools.logging.CuiLogger;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketClientOptions;
import io.vertx.core.net.TrustOptions;
import org.jspecify.annotations.Nullable;

/**
 * Builds the edge's governed egress clients — the shared Vert.x {@link HttpClient} of every upstream
 * target tuple and the one edge-wide {@link WebSocketClient} — and binds the gateway-global
 * {@code egress_tls} settings to each of them.
 * <p>
 * The egress-TLS settings are gateway-global by necessity, not by convenience (ADR-0040): both are
 * fixed at client construction, and one of the governed clients is the edge-wide WebSocket client,
 * which no per-route value could bind. So they are resolved ONCE, when this object is built, and
 * handed to every construction site — the two {@link #clientFor} branches and that WebSocket client.
 * There is no per-route override and no per-request one; Vert.x offers neither. The
 * {@code egress_tls} block governs those three Vert.x clients and no others. It does NOT reach the
 * asset-origin leg: {@code UpstreamAssetSource.httpFetcher} builds a JDK
 * {@code java.net.http.HttpClient}, which carries neither of these settings, so an https asset origin
 * keeps full hostname verification and the JVM default trust store whatever this block says.
 * <p>
 * Built once at boot and immutable afterwards; the clients it creates are Vert.x's own thread-safe
 * clients.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class EgressClients {

    private static final CuiLogger LOGGER = new CuiLogger(EgressClients.class);

    private final Vertx vertx;
    private final boolean upstreamVerifyHostname;
    private final @Nullable TrustOptions upstreamTrustOptions;

    /**
     * Resolves the gateway-global egress-TLS settings once, warning about each relaxed posture.
     *
     * @param vertx                      the Vert.x instance every egress client is created on
     * @param gatewayConfig              the bound gateway document supplying the {@code egress_tls}
     *                                   block
     * @param egressTrustProfileResolver the seam binding {@code egress_tls.upstream_tls_profile}'s
     *                                   logical name to the deployment's trust anchors; consulted
     *                                   here, and only when the document names a profile
     */
    public EgressClients(Vertx vertx, GatewayConfig gatewayConfig,
            EgressTrustProfileResolver egressTrustProfileResolver) {
        this.vertx = vertx;
        EgressTlsConfig egressTls = egressTlsOf(gatewayConfig);
        this.upstreamVerifyHostname = egressTls.upstreamVerifyHostname();
        String upstreamTlsProfile = egressTls.upstreamTlsProfile();
        // Both postures boot with a WARN and never a refusal, exactly as BROAD_TRUSTED_PROXY (102) and
        // MANAGEMENT_PLAIN_HTTP (115) do: each is a legitimate deployment, so blocking it would be
        // wrong — but neither may reach production silently.
        if (!upstreamVerifyHostname) {
            LOGGER.warn(ConfigLogMessages.WARN.EGRESS_HOSTNAME_VERIFICATION_DISABLED);
        }
        if (upstreamTlsProfile != null) {
            LOGGER.warn(ConfigLogMessages.WARN.EGRESS_TRUST_PROFILE_IN_EFFECT, upstreamTlsProfile);
        }
        // Only a NAMED profile reaches the resolver. With none named, no setTrustOptions call is made
        // at any of the three sites, so the clients keep the JVM default trust store byte-for-byte as
        // before — the resolved anchors REPLACE a client's trust rather than adding to it, so binding
        // an unrequested profile would silently narrow what every terminated upstream dial trusts.
        this.upstreamTrustOptions = upstreamTlsProfile == null
                ? null
                : egressTrustProfileResolver.resolve(upstreamTlsProfile);
    }

    /**
     * Builds the shared Vert.x client for an upstream-target tuple, bound to the gateway-global
     * egress-TLS settings. Handed to the {@link de.cuioss.sheriff.gateway.edge.assembly.RouteAssembler}
     * as its {@link de.cuioss.sheriff.gateway.edge.assembly.UpstreamClientFactory}.
     *
     * @param target the upstream-target tuple
     * @return a new client for the tuple
     */
    public HttpClient clientFor(UpstreamTarget target) {
        return clientFor(vertx, target, upstreamVerifyHostname, upstreamTrustOptions);
    }

    /**
     * Builds the edge-wide {@link WebSocketClient}. There is one for the whole edge:
     * {@code HttpClient.webSocket(...)} is deprecated in favour of the dedicated client, and the
     * dialer carries no per-route state — the upstream host, port, TLS flag and URI all ride on the
     * per-dial {@code WebSocketConnectOptions}. It does carry the gateway-global egress-TLS values,
     * because {@code WebSocketConnectOptions} declares neither a hostname-verification nor a trust
     * field: a {@code wss://} relay honours them only if they are bound here, at client construction.
     *
     * @return a new WebSocket client bound to the egress-TLS settings
     */
    public WebSocketClient webSocketClient() {
        WebSocketClientOptions webSocketClientOptions =
                new WebSocketClientOptions().setVerifyHost(upstreamVerifyHostname);
        if (upstreamTrustOptions != null) {
            webSocketClientOptions.setTrustOptions(upstreamTrustOptions);
        }
        return vertx.createWebSocketClient(webSocketClientOptions);
    }

    /**
     * Resolves the global {@code egress_tls} block into the settings every egress client is built
     * with. An absent block yields {@link EgressTlsConfig#defaults()} — both flags on, no profile —
     * which is exactly the pre-existing behaviour: hostname verification enabled and the JVM default
     * trust store. An absent KEY inside a present block is resolved to {@code true} one layer
     * earlier, by {@code ConfigLoader}'s {@code EgressTlsDeserializer}, so this method never sees the
     * primitive {@code false} Jackson would otherwise bind.
     */
    private static EgressTlsConfig egressTlsOf(GatewayConfig gatewayConfig) {
        EgressTlsConfig egressTls = gatewayConfig.egressTls();
        return egressTls == null ? EgressTlsConfig.defaults() : egressTls;
    }

    /**
     * Builds the shared Vert.x client for an upstream-target tuple. A gRPC route's tuple carries the
     * {@code forcedHttp2} flag, so it gets a client forced to HTTP/2 (h2 over TLS with ALPN, or
     * prior-knowledge h2c in cleartext) — gRPC requires HTTP/2 end-to-end. Every other tuple gets the
     * default client (HTTP/1.1 with h2 upgrade negotiation).
     * <p>
     * Both branches construct from an options object so both bind the gateway-global egress-TLS
     * settings. The default branch's object starts from a bare {@code new HttpClientOptions()} — the
     * same defaults the no-arg {@code createHttpClient()} uses — and carries {@code setVerifyHost},
     * {@code setTrustOptions} only under a configured profile, and the abortive close below. It sets no
     * protocol version, no client-level SSL flag and no change to h2-upgrade negotiation: a route's TLS
     * is still selected per request ({@code DispatchStage}), so a client-level flag here would change
     * behaviour rather than preserve it.
     * <p>
     * <strong>The default branch closes its connections abortively</strong> ({@code SO_LINGER 0}). On
     * HTTP/1.1 the gateway's own reset of an upstream request — an early upstream answer to an upload
     * still in flight, a cap breach, a failed or short inbound body, an abandoned attempt, a relay that
     * failed to start — costs the whole connection, because HTTP/1.1 has no message-scoped cancel. A
     * connection the gateway closes in order holds its local port in {@code TIME_WAIT} for a minute, so
     * a client driving such resets at rate, unauthenticated on a public route, could exhaust the
     * gateway's ephemeral ports; the connect failures that followed would open the per-upstream breaker
     * for every client. Closed abortively, a connection holds no local port once it is gone. The
     * accepted cost: for every connection this client closes, idle-pool evictions included, the
     * upstream sees a TCP reset instead of a FIN and, on TLS, no {@code close_notify}. The forced-HTTP/2
     * branch is left as it is: an HTTP/2 connection is shared by its streams, and the gateway resets a
     * single stream rather than closing the connection.
     */
    private static HttpClient clientFor(Vertx vertx, UpstreamTarget target,
            boolean verifyHostname, @Nullable TrustOptions trustOptions) {
        if (!target.forcedHttp2()) {
            return vertx.createHttpClient(
                    egressTlsBound(new HttpClientOptions().setSoLinger(0), verifyHostname, trustOptions));
        }
        HttpClientOptions options = new HttpClientOptions().setProtocolVersion(HttpVersion.HTTP_2);
        if ("https".equalsIgnoreCase(target.scheme())) {
            options.setSsl(true).setUseAlpn(true);
        } else {
            // Prior-knowledge h2c: skip the HTTP/1.1 Upgrade dance and speak HTTP/2 in cleartext.
            options.setHttp2ClearTextUpgrade(false);
        }
        return vertx.createHttpClient(egressTlsBound(options, verifyHostname, trustOptions));
    }

    /**
     * Applies the gateway-global egress-TLS settings to an HTTP client options object, so both
     * {@link #clientFor} branches bind them identically rather than each spelling the pair out.
     * {@code setTrustOptions} is applied only when a profile was configured: a named profile
     * <em>replaces</em> the client's anchors, so passing {@code null} unconditionally is not the same
     * no-op as omitting the call.
     */
    private static HttpClientOptions egressTlsBound(HttpClientOptions options, boolean verifyHostname,
            @Nullable TrustOptions trustOptions) {
        options.setVerifyHost(verifyHostname);
        if (trustOptions != null) {
            options.setTrustOptions(trustOptions);
        }
        return options;
    }
}
