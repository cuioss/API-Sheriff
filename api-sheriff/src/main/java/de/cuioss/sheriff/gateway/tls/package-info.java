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
/**
 * The accept-time TLS edge: the Vert.x {@link io.vertx.core.net.NetServer} front listener that
 * reassembles the full TLS ClientHello (RFC 6066), reads the SNI, and either opaquely L4-relays a
 * {@code tls.passthrough_sni} match to the topology-resolved backend (the gateway never handshakes)
 * or hands the still-encrypted stream to the internal terminated Quarkus HTTPS listener.
 * <p>
 * Fail-closed (GW-06): the full ClientHello is reassembled before any decision, and an
 * empty/unresolved/malformed SNI always takes the terminated-strict path — never a passthrough. When
 * {@code tls.passthrough_sni} is empty the front listener is never started, so the default
 * single-listener topology is unchanged (zero-overhead default).
 * <p>
 * This package is bound to the platform: it uses Vert.x, the Quarkus TLS registry and CDI types
 * directly. ADR-0062 keeps a hand-written component only with a recorded reason, and this package
 * has one such component, {@link ClientHelloSniParser}. Its reason is recorded below.
 *
 * <h2>Which classes are CDI beans</h2>
 *
 * A class of this package is an {@code @ApplicationScoped} bean where the container has to find it or
 * call it: to look it up as an {@code HttpServerOptionsCustomizer} before it builds the listeners, to
 * deliver the startup or the shutdown event to it, or to inject the TLS registry into it. The
 * annotations on the classes are the record of which ones those are.
 * <p>
 * {@link SniFrontListener}, {@link PassthroughRelay} and {@link ClientHelloSniParser} are deliberately
 * not beans. They are created only when {@code tls.passthrough_sni} is non-empty:
 * {@link TlsEdgeProducer} creates the listener and the relay, and the listener creates the parser. As
 * beans they would exist in every deployment, including the default one that starts no front
 * listener.
 * <p>
 * {@link RelayedPeerRegistry} carries no class annotation, yet it is a bean: {@link TlsEdgeProducer}
 * produces the one instance it owns as a {@code @Singleton}, in every deployment, so the terminated
 * relay and the edge share it. For this one class the producer method, not a class annotation, is the
 * record. The scope is {@code @Singleton} rather than {@code @ApplicationScoped} because the class is
 * {@code final} and so takes no client proxy.
 *
 * <h2>Why {@code ClientHelloSniParser} is hand-written</h2>
 *
 * The review of this package against ADR-0062 confirmed the parser from the code; it is not replaced.
 * Vert.x exposes the SNI only on a connection it has terminated: {@code NetSocket#indicatedServerName()}
 * is filled by the TLS handshake of an SSL-enabled server. A passthrough connection must not be
 * terminated, because the gateway relays it unchanged at L4 and the backend presents its own
 * certificate to the client (ADR-0017). {@link SniFrontListener} is therefore a plain-TCP
 * {@code NetServer}. Each accepted connection owns its own incremental parser and offers it every
 * received chunk exactly once; the parser consumes only the TLS records each chunk completes and reads
 * the {@code server_name} extension out of the cleartext ClientHello before any handshake, and
 * {@link PassthroughRelay} then replays the same bytes, kept in a per-connection buffer, to the chosen
 * target. No platform API yields the name at that point.
 *
 * <h2>The bounds of the front</h2>
 *
 * The front closes a connection that has not completed its ClientHello within a fixed ten-second
 * deadline, and refuses a connection at accept while 1024 accepted connections are still waiting —
 * for their ClientHello, or, once routed, for their relay's backend dial to settle — or while its
 * source already holds its share of a quarter of them. That dial window spans the backend's host-name
 * lookup and a ten-second connect timeout, and one passthrough target may have at most an eighth of the
 * cap dialling at once: a connection routed to a target whose dial budget is full is refused at its
 * route decision. Every relayed leg is bounded by a Vert.x idle timeout that {@link TlsEdgeProducer}
 * derives at boot so it never undercuts an idle bound inside the gateway. {@link SniFrontListener}
 * documents the deadline, the cap, the per-source share and the dial budget, {@link TlsEdgeProducer}
 * the connect timeout and the derivation of the idle timeout; none of them is configurable.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.tls;

import org.jspecify.annotations.NullMarked;
